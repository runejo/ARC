package fr.insee.arc.core.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.log4j.Logger;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarInputStream;
import org.springframework.stereotype.Component;

import fr.insee.arc.core.model.TraitementRapport;
import fr.insee.arc.core.model.TraitementState;
import fr.insee.arc.core.model.TraitementTypeFichier;
import fr.insee.arc.core.model.TypeTraitementPhase;
import fr.insee.arc.core.util.EDateFormat;
import fr.insee.arc.utils.dao.UtilitaireDao;
import fr.insee.arc.utils.structure.GenericBean;
import fr.insee.arc.utils.utils.FormatSQL;
import fr.insee.arc.utils.utils.LoggerDispatcher;
import fr.insee.arc.utils.utils.LoggerHelper;
import fr.insee.arc.utils.utils.ManipString;
import fr.insee.arc.utils.utils.SQLExecutor;

/**
 * ApiReceptionService
 *
 *     	1- Déplacer les enveloppes de l'entrepot vers EN_COURS. Horodater et archiver les enveloppes recues</br>
 * 		2- Enregistrer n enveloppes dans la base pour traitement
 * 			2-1 Vérification de l'intégrité de l'enveloppe
 * 			2_2 Identification des doublons de fichier
 * 			2-2 Enregistrement des fichiers et de leur enveloppe dans la table de pilotage
 *
 * @author Manuel SOULIER
 *
 */
@Component
public class ApiReceptionService extends AbstractPhaseService  implements IApiServiceWithoutOutputTable  {
	public ApiReceptionService() {
		super();
	}

	private static final Logger LOGGER = Logger.getLogger(ApiReceptionService.class);
	
	//Expression régulière correspondant au nom des fichiers temporaires 
	//transmis via le flux Oriade (soit XXXXXX-W, avec X dans [A-Z])
	private static final Pattern p = Pattern.compile("^[A-Z]{6}-W.*");
	
	
	public ApiReceptionService(String aCurrentPhase, String anParametersEnvironment, String aexecutionEnv, String aDirectoryRoot, Integer aNbEnr, String... paramBatch) {
		super(aCurrentPhase, anParametersEnvironment, aexecutionEnv, aDirectoryRoot, aNbEnr, paramBatch);
	}


	public ApiReceptionService(Connection connexion,String aCurrentPhase, String anParametersEnvironment, String aexecutionEnv, String aDirectoryRoot, Integer aNbEnr, String... paramBatch) {
	    super(connexion, aCurrentPhase, anParametersEnvironment, aexecutionEnv, aDirectoryRoot, aNbEnr, paramBatch);
	}


	@Override
	public void process() {
		// Déplacement et archivage des fichiers
		moveClientFiles(this.nbEnr);
		// Enregistrement des fichiers
		registerFiles(this.connection, this.executionEnv, this.directoryRoot);
	}

	    @SQLExecutor
	public void moveClientFiles(int fileSizeLimit) {
		LoggerDispatcher.info("moveClientFiles", LOGGER);
		String receptionDirectoryRoot = this.directoryRoot + this.executionEnv.toUpperCase().replace(".", "_") + File.separator
				+ TypeTraitementPhase.REGISTER;
		
		try {
			// vérifier que les répertoires cible existent; sinon les créer
			UtilitaireDao.createDirIfNotexist(receptionDirectoryRoot + "_" + TraitementState.ENCOURS);
			UtilitaireDao.createDirIfNotexist(receptionDirectoryRoot + "_" + TraitementState.OK);
			UtilitaireDao.createDirIfNotexist(receptionDirectoryRoot + "_" + TraitementState.KO);
			// déplacer les fichiers du répertoire de l'entrepot vers le répertoire encours
			HashMap<String, ArrayList<String>> entrepotList = new GenericBean(UtilitaireDao.get("arc").executeRequest(this.connection,
					"select id_entrepot from arc.ihm_entrepot")).mapContent();
			
			// pour limiter le nombre de fichier simultanés : taillemax*coeff
			int coeffNb=50;
		
			if (!entrepotList.isEmpty())
			{
			
			String dirIn;
			String dirArchive;
			File fDirIn;
			String dirOut = receptionDirectoryRoot + "_" + TraitementState.ENCOURS;

			int fileSize = 0;
			int fileNb = 0;
			
			LoggerDispatcher.info("Taille limite de fichiers à charger : " + fileSizeLimit,LOGGER);

			for (String d : entrepotList.get("id_entrepot")) {

				if (fileSize >= fileSizeLimit || fileNb> fileSizeLimit*coeffNb) {
					this.reporting=fileNb;
					break;
				}

				dirIn = receptionDirectoryRoot + "_" + d;
				dirArchive = receptionDirectoryRoot + "_" + d + "_ARCHIVE";
				fDirIn = new File(dirIn);
				// créer le répertoire de l'entrepot et son repertoire archive
				UtilitaireDao.createDirIfNotexist(dirArchive);
				UtilitaireDao.createDirIfNotexist(fDirIn);
				// vérifier le type (répertoire)
				if (fDirIn.isDirectory()) {
					
					File[] filesDirIn=fDirIn.listFiles(); 
					
					// trier par nom
					Arrays.sort(filesDirIn, new Comparator<File>(){
					    public int compare(File f1, File f2)
					    {
					        return f1.getName().compareTo(f2.getName());
					    } });
					
					
					
					for (File f : filesDirIn) {

						// traiter le fichier
						// s'il n'est pas en cours d'ecriture
						// si ce n'est pas un fichier temporaire Oriade
						// si ce n'est pas le fichier de déclenchement d'une mise en production
						Matcher matcher = p.matcher(f.getName());
						
						if (fr.insee.arc.utils.files.FileUtils.isCompletelyWritten(f)
								// oriade : format des fichiers temporaires
//								&& !f.getName().endsWith(ApiService.SUFFIXE_TEMP_FILE_ORIADE)
								&& !matcher.matches()
								&& !f.getName().equals(AbstractPhaseService.PRODUCTION_FILE)
								)
						{
							if (fileSize >= fileSizeLimit || fileNb>fileSizeLimit*coeffNb) {
								this.reporting=fileNb;
								break;
							}

							// Archiver le fichier
							// on regarde si le fichier existe déjà dans le repertoire archive; si c'est le cas, on va renommer
							String fname;
							

								for (int i=1;i<1000000;i++)
								{

									// on reprend le nom du fichier
									fname=f.getName();
									boolean isArchive=true;
									
									// les fichiers non archive sont archivés
			                		if (UtilitaireDao.isNotArchive(fname))
			                		{
			                			fname=fname+".tar.gz";
			                			isArchive=false;
			                		}

									// on ajoute un index au nom du fichier toto.tar.gz devient toto#1.tar.gz
									if (i>1)
									{
										fname = ManipString.substringBeforeFirst(fname, ".") + "#" + i + "." + ManipString.substringAfterFirst(fname, ".");
									}


									File fileOutArchive = new File(dirArchive + File.separator + fname );

									// si le fichier n'existe pas dans le repertoire d'archive
									// on le copie dans archive avec son nouveau nom
									// on change le nom du fichier initial avec son nouveau nom indexé
									// on le déplace dans encours
									// on enregistre le fichier dans la table d'archive
									// on sort de la boucle d'indexation
									if (!fileOutArchive.exists())
									{

										if (isArchive)
										{
											// copie dans archive avec le nouveau nom
											Files.copy(Paths.get(f.getAbsolutePath()), Paths.get(fileOutArchive.getAbsolutePath()));
											// déplacer le fichier dans encours
											deplacerFichier(dirIn, dirOut, f.getName(), d + "_" + fname);
										}
										else
										{
											// on génére le tar.gz dans archive
											UtilitaireDao.generateTarGzFromFile(f, fileOutArchive, f.getName());
											// on copie le tar.gz dans encours
											File fOut=new File(dirOut + File.separator + d + "_"+ fname);
											Files.copy(Paths.get(fileOutArchive.getAbsolutePath()), Paths.get(fOut.getAbsolutePath()));
											// on efface le fichier source
											f.delete();
										}
										
										fileSize=fileSize+(int)(fileOutArchive.length()/1024/1024);
										fileNb=fileNb+1;

										
										// enregistrer le fichier
										UtilitaireDao.get("arc").executeBlock(
												this.connection,
												"INSERT INTO " + dbEnv(this.executionEnv) + "pilotage_archive (entrepot,nom_archive) values ('" + d + "','" + fname
												+ "'); ");

										// nbFile++;
										break;
									}


								}

						}
					}
				}
			}
			}		
		} catch (Exception ex) {
		    LoggerHelper.error(LOGGER, ex, "moveClientFiles()" );
		}
	}

	/**
	 * Enregistrer les fichiers en entrée Déplacer les fichier reçus dans les repertoires OK ou pas OK selon le bordereau Supprimer les
	 * fichiers déjà existants de la table de pilotage Marquer les fichiers dans la table de pilotage
	 */
	public void registerFiles(Connection connexion, String anExecutionEnvironment, String directoryRoot) {
		LoggerDispatcher.info("registerFiles", LOGGER);
		String receptionDirectoryRoot = directoryRoot + anExecutionEnvironment.toUpperCase().replace(".", "_") + File.separator
				+ TypeTraitementPhase.REGISTER;
		// on considère tous les fichiers du repertoire reception en cours
		File folder = new File(receptionDirectoryRoot + "_" + TraitementState.ENCOURS);
		File[] filesIn = folder.listFiles();
		// la bean (fileName,type, etat) contient pour chaque fichier, le type
		// du fichier et l'action à réaliser
		LoggerDispatcher.info("dispatchFiles", LOGGER);
		GenericBean g = dispatchFiles(filesIn);
		
		try {
		
		StringBuilder requete = new StringBuilder();
		requete.append(FormatSQL.dropTable(this.getTablePilTemp()));
		requete.append(creationTableResultat(this.getTablePil(), this.getTablePilTemp()));
		soumettreRequete(requete);

		if (!g.content.isEmpty()) {
			String dirIn = receptionDirectoryRoot + "_" + TraitementState.ENCOURS;
			for (int i = 0; i < g.content.size(); i++) {
				String container = g.content.get(i).get(g.headers.indexOf("container"));
				String v_container = g.content.get(i).get(g.headers.indexOf("v_container"));
				String fileName = g.content.get(i).get(g.headers.indexOf("fileName"));
				String type = g.content.get(i).get(g.headers.indexOf("type"));
				String etat = g.content.get(i).get(g.headers.indexOf("etat"));
				String rapport = g.content.get(i).get(g.headers.indexOf("rapport"));
				String containerNewName = buildContainerName(container, v_container);
				if (type.equals(TraitementTypeFichier.DA.toString())) {
					insertPilotage(requete,  this.getTablePilTemp(), container, containerNewName, v_container, fileName, etat, rapport);
				}
				if (type.equals(TraitementTypeFichier.A.toString())) {
					String dirOut = receptionDirectoryRoot + "_" + etat;
					deplacerFichier(dirIn, dirOut, container, containerNewName);
				}
				if (type.equals(TraitementTypeFichier.AC.toString())) {
					String dirOut = receptionDirectoryRoot + "_" + etat;
					deplacerFichier(dirIn, dirOut, container, containerNewName);
					insertPilotage(requete,  this.getTablePilTemp(), container, containerNewName, v_container, fileName, etat, rapport);
				}
				// pour les fichier seul, on en fait une archive
				if (type.equals(TraitementTypeFichier.D.toString())) {
					// String dirOut = receptionDirectoryRoot + "_" + etat;
					// en termes de destination, les fichiers seuls vont tout le temps dans RECEPTION_OK, même s'ils sont KO pour la table
					// de pilotage
					String dirOut = receptionDirectoryRoot + "_" + TraitementState.OK;
					File fileIn = new File(dirIn + "/" + fileName);
					File fileOut = new File(dirOut + "/" + containerNewName);
				    
					if (fileOut.exists()) {
						fileOut.delete();
					}
						UtilitaireDao.generateTarGzFromFile(fileIn, fileOut, ManipString.substringAfterFirst(fileIn.getName(), "_"));
						fileIn.delete();
						insertPilotage(requete,  this.getTablePilTemp(), container, containerNewName, v_container, fileName, etat, rapport);

				}
			}
			requete.append(";");
			soumettreRequete(requete);

	        boolean fichierARejouer=UtilitaireDao.get("arc").hasResults(connexion, "select 1 from " +  this.getTablePil() + " where phase_traitement='RECEPTION' and to_delete in ('R','F') limit 1;");

			if (fichierARejouer)
			{
				// marque les fichiers à effacer (ils vont etre rechargés)
				requete.append("CREATE TEMPORARY TABLE a_rejouer "+FormatSQL.WITH_NO_VACUUM+" as select distinct id_source from "+this.getTablePil()+" a where to_delete='R' and exists (select 1 from " + this.getTablePilTemp() + " b where a.id_source=b.id_source); ");
				
				// balayer toutes les tables; effacer les enregistrements 
	
	            g = new GenericBean(UtilitaireDao.get("arc").executeRequest(connexion, ApiInitialisationService.requeteListAllTablesEnv(executionEnv)));
	            if (!g.mapContent().isEmpty()) {
	                ArrayList<String> envTables = g.mapContent().get("table_name");
	                for (String nomTable : envTables) {
	                    
	                	requete.append("DELETE FROM "+nomTable+" a where exists (select 1 from a_rejouer b where a.id_source=b.id_source); ");
	                    requete.append("vacuum "+nomTable+"; ");
	                    
	                    if (!nomTable.contains(TypeTraitementPhase.MAPMODEL.toString().toLowerCase())
	                            && nomTable.endsWith("_" + TraitementState.OK.toString().toLowerCase())) {
	                    requete.append("DELETE FROM "+nomTable+"_todo a where exists (select 1 from a_rejouer b where a.id_source=b.id_source); ");
	                    requete.append("vacuum "+nomTable+"_todo; ");	                    
	                    }

	                }
	            }
				
				// effacer de la table pilotage des to_delete à R
				requete.append("DELETE FROM " + this.getTablePil() + " a using a_rejouer b where a.id_source=b.id_source; ");
			}

			
			// pb des archives sans nom de fichier
			requete.append("UPDATE " + this.getTablePilTemp() + " set id_source='' where id_source is null; ");
			requete.append("INSERT INTO " + this.getTablePil() + " select * from " + this.getTablePilTemp() + "; \n");
			requete.append("DISCARD TEMP; \n");
			soumettreRequete(requete);

//			 maintenancePilotage(connexion,this.executionEnv, "");
		}
		} catch (Exception ex) {
		    LoggerHelper.error( LOGGER, ex, "registerFiles()");
		}
	}

	public String buildContainerName(String container, String v_container) {
		String newContainerName = new String();
		newContainerName = new String();
		if (container.endsWith(".tar.gz")) {
			newContainerName = normalizeContainerName(container, v_container, ".tar.gz");
		} else if (container.endsWith(".tgz")) {
			newContainerName = normalizeContainerName(container, v_container, ".tgz");
		} else if (container.endsWith(".zip")) {
			newContainerName = normalizeContainerName(container, v_container, ".zip");
		} else if (container.endsWith(".gz")) {
			newContainerName = normalizeContainerName(container, v_container, ".gz");
		} else if (container.endsWith(".tar")) {
			newContainerName = normalizeContainerName(container, v_container, ".tar");
		}
		return newContainerName;
	}

	public String normalizeContainerName(String container, String v_container, String extension) {
		//String newContainerName = ManipString.substringBeforeLast(container, extension) + "#" + v_container + extension;

		String newContainerName = ManipString.substringBeforeLast(container, extension) +  extension;


		return newContainerName;
	}

	public void soumettreRequete(StringBuilder requete) {
		try {
			UtilitaireDao.get("arc").executeImmediate(this.connection, requete);
		} catch (SQLException ex) {
		    LoggerHelper.error( LOGGER, ex, "soumettreRequete()");
		}
		requete.setLength(0);
	}

	public void insertPilotage(StringBuilder requete, String tablePilotage, String originalContainer, String newContainer, String v_container,
			String fileName, String etat, String rapport) {
		Date d = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat(EDateFormat.DATE_FORMAT_WITH_HOUR.getValue());
		SimpleDateFormat formatter = new SimpleDateFormat(EDateFormat.DATE_FORMAT_WITH_SECOND.getValue());
		
		// si ko, etape vaut 2
		String etape=etat.equals(TraitementState.KO.toString())?"2":"1";
		
		if (requete.length()==0)
		{		
			requete.append("INSERT INTO " + tablePilotage + " ");
			requete.append("(o_container, container, v_container, id_source,date_entree,phase_traitement,etat_traitement,date_traitement, rapport, nb_enr, etape) VALUES ");
		}
		else
		{
			requete.append("\n,");
		}
		requete.append(" (" + FormatSQL.cast(originalContainer) + "," + FormatSQL.cast(newContainer) + "," + FormatSQL.cast(v_container) + ", "
				+ FormatSQL.cast(fileName) + "," + FormatSQL.cast(dateFormat.format(d)) + "," + FormatSQL.cast(TypeTraitementPhase.REGISTER.toString())
				+ "," + FormatSQL.cast("{" + etat + "}") + "," + FormatSQL.cast(formatter.format(d)) + "," + FormatSQL.cast(rapport) + ",1,"+etape+") ");
	}

	/**
	 * Deplacer un fichier d'un repertoire source vers répertoire cible (pas de slash en fin du nom de repertoire) Si le fichier existe
	 * déjà, il est écrasé
	 *
	 * @param dirIn
	 *            , répertoire en entrée, pas de slash à la fin
	 * @param dirOut
	 *            , répertoire en sortie, pas de slash à la fin
	 * @param FileName
	 *            , nom du fichier
	 */
	public static void deplacerFichier(String dirIn, String dirOut, String FileNameIn, String fileNameOut) {
//		LoggerDispatcher.info("Mes paramètres de déplacement de fichier : \n dirIn : " + dirIn + " \n, dirOut : " + dirOut + " \n, FilenameIn :"
//				+ FileNameIn + " \n, FilenameOut :" + fileNameOut, logger);
		if (!dirIn.equals(dirOut)) {
			File fileIn = new File(dirIn + "/" + FileNameIn);
			File fileOut = new File(dirOut + "/" + fileNameOut);
			if (fileOut.exists()) {
				fileOut.delete();
			}
			fileIn.renameTo(fileOut);
		}
	}

	/**
	 * Permet de trier les fichiers (par rapport au bordereau, date, etc...) Renvoie un GenericBean : fileName, type (D=Data,B=Bordereau),
	 * etat (ok, ko, encours)
	 *
	 * @param FilesIn
	 * @return
	 */
	public GenericBean dispatchFiles(File[] FilesIn) {
		ArrayList<String> headers = new ArrayList<>(Arrays.asList("container", "fileName", "type", "etat", "rapport", "v_container"));
		ArrayList<String> types = new ArrayList<>(Arrays.asList("text", "text", "text", "text", "text", "text"));
		ArrayList<ArrayList<String>> content = new ArrayList<>();
		ArrayList<ArrayList<String>> contentTemp = new ArrayList<>();
		ArrayList<String> l;
		for (File f : FilesIn) {
			String entrepot = ManipString.substringBeforeFirst(f.getName(), "_") + "_";
			if (f.getName().endsWith(".tar.gz") || f.getName().endsWith(".tgz")) {
				// Inscription des fichier au contgenu de l'archive
				contentTemp.clear();
				Integer erreur = 0;
				String rapport = null;
				String etat = null;
				// vérifier si l'archive est illisible dans son ensemble
				try {
					FileInputStream fis = new FileInputStream(f);
					try {
						GZIPInputStream gzis = new GZIPInputStream(fis);
						try {
							TarInputStream tarInput = new TarInputStream(gzis);
							try {
								erreur = 1;
								rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
								TarEntry currentEntry = tarInput.getNextEntry();
								// boucle sur les entries
								while (currentEntry != null) {
									l = new ArrayList<String>();
									l.add(f.getName());
									l.add(entrepot + currentEntry.getName());
									l.add(TraitementTypeFichier.DA.toString());
									// vérifier si l'entry est lisible (on
									// appelle nextEntry)
									erreur = 0;
									etat = TraitementState.OK.toString();
									rapport = null;
									try {
										currentEntry = tarInput.getNextEntry();
									} catch (IOException e) {
										erreur = 2;
										etat = TraitementState.KO.toString();
										rapport = TraitementRapport.INITIALISATION_CORRUPTED_ENTRY.toString();
										currentEntry = null;
									}
									l.add(etat);
									l.add(rapport);
									l.add(null);
									contentTemp.add(l);
									rapport = null;
								}
							} finally {
								tarInput.close();
								gzis.close();
								fis.close();
							}
						} catch (IOException e1) {
							erreur = 1;
							rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
							gzis.close();
							fis.close();
						}
					} catch (IOException e1) {
						erreur = 1;
						rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
						fis.close();
					}
				} catch (IOException e1) {
					erreur = 1;
					rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
				}
				// Inscription de l'archive
				l = new ArrayList<String>();
				l.add(f.getName());
				l.add(null);
				if (erreur == 1) {
					l.add(TraitementTypeFichier.AC.toString());
				} else {
					l.add(TraitementTypeFichier.A.toString());
				}
				if (erreur > 0) {
					l.add(TraitementState.KO.toString());
				} else {
					l.add(TraitementState.OK.toString());
				}
				l.add(rapport);
				l.add(null);
				contentTemp.add(l);
				// Mise à jour du code etat : si erreur vaut true : on met tout
				// en erreur sinon, tout en ok
				for (ArrayList<String> z : contentTemp) {
					if (erreur > 0) {
						z.set(headers.indexOf("etat"), TraitementState.KO.toString());
					} else {
						z.set(headers.indexOf("etat"), TraitementState.OK.toString());
					}
				}
				content.addAll(contentTemp);
				contentTemp.clear();
			}
			// enveloppe zip
			else if (f.getName().endsWith(".zip")) {
				// Inscription des fichier au contgenu de l'archive
				contentTemp.clear();
				Integer erreur = 0;
				String rapport = null;
				String etat = null;
				// vérifier si l'archive est illisible dans son ensemble
				try {
					FileInputStream fis = new FileInputStream(f);
					try {
						ZipArchiveInputStream tarInput = new ZipArchiveInputStream(fis);
						try {
							// boucle sur les entries
							erreur = 1;
							rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
							ZipArchiveEntry currentEntry = tarInput.getNextZipEntry();
							while (currentEntry != null) {
								l = new ArrayList<String>();
								l.add(f.getName());
								l.add(entrepot + currentEntry.getName());
								l.add(TraitementTypeFichier.DA.toString());
								// vérifier si l'entry est lisible (on appelle
										// nextEntry)
										erreur = 0;
								etat = TraitementState.OK.toString();
								rapport = null;
								try {
									currentEntry = tarInput.getNextZipEntry();
								} catch (IOException e) {
									erreur = 2;
									etat = TraitementState.KO.toString();
									rapport = TraitementRapport.INITIALISATION_CORRUPTED_ENTRY.toString();
									currentEntry = null;
								}
								// vérifier si l'entry suivante est accessible;
								// sinon on met
								// à vide pour arreter la boucle
								l.add(etat);
								l.add(rapport);
								l.add(null);
								contentTemp.add(l);
								rapport = null;
							}
						} finally {
							tarInput.close();
							fis.close();
						}
					} catch (IOException e1) {
						erreur = 1;
						rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
						fis.close();
					}
				} catch (IOException e1) {
					erreur = 1;
					rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
				}
				// Inscription de l'archive
				l = new ArrayList<String>();
				l.add(f.getName());
				l.add(null);
				if (erreur == 1) {
					l.add(TraitementTypeFichier.AC.toString());
				} else {
					l.add(TraitementTypeFichier.A.toString());
				}
				if (erreur > 0) {
					l.add(TraitementState.KO.toString());
				} else {
					l.add(TraitementState.OK.toString());
				}
				l.add(rapport);
				l.add(null);
				contentTemp.add(l);
				// Mise à jour du code etat : si erreur vaut true : on met tout
				// en erreur sinon, tout en ok
				for (ArrayList<String> z : contentTemp) {
					if (erreur > 0) {
						z.set(headers.indexOf("etat"), TraitementState.KO.toString());
					} else {
						z.set(headers.indexOf("etat"), TraitementState.OK.toString());
					}
				}
				content.addAll(contentTemp);
				contentTemp.clear();
			} else if (f.getName().endsWith(".gz")) {
				// Inscription des fichier au contgenu de l'archive
				contentTemp.clear();
				Integer erreur = 0;
				String rapport = null;
				String etat = null;
				// vérifier si l'archive est illisible dans son ensemble
				try {
					FileInputStream fis = new FileInputStream(f);
					try {
						GZIPInputStream tarInput = new GZIPInputStream(fis);
						try {
							l = new ArrayList<String>();
							l.add(f.getName());
							l.add(ManipString.substringBeforeLast(f.getName(), ".gz"));
							l.add(TraitementTypeFichier.DA.toString());
							// vérifier si l'entry est lisible
							try {
								tarInput.read();
								// for (int c = tarInput.read(); c != -1; c =
								// tarInput.read()) {}
								erreur = 0;
								etat = TraitementState.OK.toString();
								rapport = null;
							} catch (IOException e) {
								erreur = 2;
								etat = TraitementState.KO.toString();
								rapport = TraitementRapport.INITIALISATION_CORRUPTED_ENTRY.toString();
							}
							l.add(etat);
							l.add(rapport);
							l.add(null);
							contentTemp.add(l);
							rapport = null;
						} finally {
							tarInput.close();
							fis.close();
						}
					} catch (IOException e1) {
						erreur = 1;
						rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
						fis.close();
					}
				} catch (IOException e1) {
					erreur = 1;
					rapport = TraitementRapport.INITIALISATION_CORRUPTED_ARCHIVE.toString();
				}
				// Inscription de l'archive
				l = new ArrayList<String>();
				l.add(f.getName());
				l.add(null);
				if (erreur == 1) {
					l.add(TraitementTypeFichier.AC.toString());
				} else {
					l.add(TraitementTypeFichier.A.toString());
				}
				if (erreur > 0) {
					l.add(TraitementState.KO.toString());
				} else {
					l.add(TraitementState.OK.toString());
				}
				l.add(rapport);
				l.add(null);
				contentTemp.add(l);
				// Mise à jour du code etat : si erreur vaut true : on met tout
				// en erreur sinon, tout en ok
				for (ArrayList<String> z : contentTemp) {
					if (erreur > 0) {
						z.set(headers.indexOf("etat"), TraitementState.KO.toString());
					} else {
						z.set(headers.indexOf("etat"), TraitementState.OK.toString());
					}
				}
				content.addAll(contentTemp);
				contentTemp.clear();
			} else {// cas rebus, hors tar.gz, zip et gz
				l = new ArrayList<String>();
				l.add(f.getName() + ".tar.gz");
				l.add(f.getName());
				l.add(TraitementTypeFichier.D.toString());
				l.add(TraitementState.OK.toString());
				l.add(null);
				l.add(null);
				LoggerDispatcher.info("Insertion du cas rebus : " + l.toString(), LOGGER);
				content.add(l);
			}
		}
		
	//	LoggerDispatcher.info("Contenu de content juste avant la localisation des doublons : " + content.toString(), logger);
		// Localiser les doublons
		// Note : l'insertion est redondante mais au niveau métier, c'est
		// beaucoup plus logique
		LoggerDispatcher.info("Recherche de doublons de fichiers", LOGGER);

		StringBuilder requete = new StringBuilder();
		requete.append(FormatSQL.dropTable(this.getTablePilTemp()));
		requete.append(creationTableResultat(this.getTablePil(), this.getTablePilTemp()));
		String fileName;
		String container;
		String type;
		// insertion des fichiers dans la table tablePilTemp
		for (int i = 0; i < content.size(); i++) {
			container = content.get(i).get(headers.indexOf("container"));
			fileName = content.get(i).get(headers.indexOf("fileName"));
			if (fileName != null) {
				requete.append("insert into " + this.getTablePilTemp() + " (container, id_source) values (" + FormatSQL.cast(container) + ","
						+ FormatSQL.cast(fileName) + "); \n");
			}
		}
		soumettreRequete(requete);
		// detection des doublons de fichiers sur les id_source juste insérés
		// faut comparer les id_sources en retirant le #nnn représentant le numéro de l'archive (on utilise le regexp_replace pour retirer le #nnn)

		requete.append("select container, id_source FROM " + this.getTablePilTemp() + " where id_source in ( ");
		requete.append("select distinct id_source from ( ");
		requete.append("select id_source, count(1) over (partition by id_source) as n from " + this.getTablePilTemp() + " ");
		requete.append(") ww where n>1 ");
		requete.append(") ");
		// detection des doublons de fichiers dans la table de pilotage
		requete.append("UNION ");
		requete.append("SELECT container, id_source from " + this.getTablePilTemp() + " a ");
		requete.append("where exists (select 1 from " + this.getTablePil() + " b where a.id_source=b.id_source) \n");
		requete.append("and a.id_source not in (select distinct id_source from " + this.getTablePil() + " b where b.to_delete='R') ;\n");
		
		// récupérer les doublons pour mettre à jour le dispatcher
		try {
			ArrayList<String> listIdsourceDoublons = new GenericBean(UtilitaireDao.get("arc").executeRequest(this.connection, requete)).mapContent().get("id_source");
			
			// on va parcourir la liste des fichiers
			// si on retrouve l'id_source dans la liste, on le marque en erreur
			if (listIdsourceDoublons != null) {
				for (ArrayList<String> z : content) {
					// si le nom de fichier est renseigné et retrouvé dans la liste
					// on passe l'état à KO et on marque l'anomalie
					if (z.get(headers.indexOf("fileName")) != null) {
						if (listIdsourceDoublons.contains(z.get(headers.indexOf("fileName")))) {
							z.set(headers.indexOf("etat"), TraitementState.KO.toString());
							z.set(headers.indexOf("rapport"), TraitementRapport.INITIALISATION_DUPLICATE.toString());
						}
					}
				}
			}
		} catch (SQLException ex) {
		    LoggerHelper.error( LOGGER, ex, "dispatchFiles()");
		}
		
		// on ignore les doublons de l'archive pour les fichiers à rejouer
		// on recrée un nouvelle liste en ne lui ajoutant pas ces doublons à ignorer
		requete = new StringBuilder();
		requete.append("SELECT container, container||'>'||id_source as id_source from " + this.getTablePilTemp() + " a ");
		requete.append("where exists (select 1 from " + this.getTablePil() + " b where to_delete='R' and a.id_source=b.id_source) ;\n");

		ArrayList<ArrayList<String>> content2 = new ArrayList<ArrayList<String>>();
		try {
			HashMap<String, ArrayList<String>> m =  new GenericBean(UtilitaireDao.get("arc").executeRequest(this.connection, requete)).mapContent();
			ArrayList<String> listContainerARejouer = m.get("container");
			ArrayList<String> listIdsourceARejouer = m.get("id_source");

			if (listIdsourceARejouer==null)
			{
				content2=content;
			}
			else
			{
			for (ArrayList<String> z : content) {
				// si le fichier est dans la liste des doublons à ignorer, on le l'ajoute pas à la nouvelle liste
				if (z.get(headers.indexOf("fileName")) != null) {
					if (listContainerARejouer.contains(z.get(headers.indexOf("container"))))
					{
						// si on trouve le fichier à rejouer, on l'ajoute; on ignore les autres
						if (listIdsourceARejouer.contains(
								z.get(headers.indexOf("container"))+">"+z.get(headers.indexOf("fileName")
										))) {
							content2.add(z);
						}
					}
					else
					{
						content2.add(z);
					}
				}
				else
				{
					// bien ajouter les caracteriqtique de l'archive à la nouvelle liste
					content2.add(z);
				}
			}
			}
		
		} catch (SQLException ex) {
		    LoggerHelper.error( LOGGER, ex, "dispatchFiles()");
		}
		content=content2;



		
		// detection des doublons d'archive. Génération d'un numéro pour
		// l'archive en cas de doublon
		
		requete = new StringBuilder();
		// insertion des fichiers d'archive corrompue dans la table
		// tablePilTemp
		// on doit aussi leur donner un numéro
		for (int i = 0; i < content.size(); i++) {
			container = content.get(i).get(headers.indexOf("container"));
			fileName = content.get(i).get(headers.indexOf("fileName"));
			type = content.get(i).get(headers.indexOf("type"));
			if (type.equals(TraitementTypeFichier.AC.toString())) {
				requete.append("insert into " + this.getTablePilTemp() + " (container, id_source) values (" + FormatSQL.cast(container) + ","
						+ FormatSQL.cast(fileName) + "); \n");
			}
		}
		soumettreRequete(requete);
				
		requete.append("select container ");
		requete.append(" , coalesce((select max(v_container::integer)+1 from  " + this.getTablePil()
				+ " b where a.container=b.o_container),1)::text as v_container ");
		requete.append("from (select distinct container from " + this.getTablePilTemp() + " where container is not null) a ");
		try {
			HashMap<String, ArrayList<String>> m = new GenericBean(UtilitaireDao.get("arc").executeRequest(this.connection, requete)).mapContent();
			ArrayList<String> listContainerDoublons = m.get("container");
			ArrayList<String> listVersionContainerDoublons = m.get("v_container");
			if (listContainerDoublons != null) {
				for (ArrayList<String> z : content) {
					container = z.get(headers.indexOf("container"));
					if (container != null) {
						z.set(headers.indexOf("v_container"), listVersionContainerDoublons.get(listContainerDoublons.indexOf(container)));
					}
				}
			}
		} catch (SQLException ex) {
		    LoggerHelper.error( LOGGER, ex, "dispatchFiles()");
		}
		requete.setLength(0);
		requete.append(FormatSQL.dropTable(this.getTablePilTemp()));
		soumettreRequete(requete);
		GenericBean g = new GenericBean(headers, types, content);
		return g;
	}

}
