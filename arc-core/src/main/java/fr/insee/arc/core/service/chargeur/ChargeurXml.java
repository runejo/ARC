package fr.insee.arc.core.service.chargeur;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.log4j.Logger;
import org.apache.tools.ant.util.XmlConstants;
import org.xml.sax.SAXException;

import fr.insee.arc.core.archive_loader.FilesInputStreamLoad;
import fr.insee.arc.core.model.Norme;
import fr.insee.arc.core.model.TraitementState;
import fr.insee.arc.core.service.AbstractPhaseService;
import fr.insee.arc.core.service.handler.XMLHandlerCharger4;
import fr.insee.arc.core.service.thread.ThreadLoadService;
import fr.insee.arc.core.util.TypeChargement;
import fr.insee.arc.core.util.XMLParserSecurityUtils;
import fr.insee.arc.utils.dao.UtilitaireDao;
import fr.insee.arc.utils.utils.FormatSQL;
import fr.insee.arc.utils.utils.LoggerDispatcher;
import fr.insee.arc.utils.utils.LoggerHelper;

/**
 * Use Sax to parse xml files
 * 
 * @author Pépin Rémi
 *
 */
public class ChargeurXml implements ILoader {
       private static final Logger LOGGER = Logger.getLogger(ChargeurXml.class);
    private String fileName;
    private HashMap<String, Integer> col = new HashMap<>();
    private Connection connection;
    private HashMap<String, Integer> colData = new HashMap<>();
    private List<String> allCols = new ArrayList<>();
    private StringBuilder requeteInsert = new StringBuilder();
    private int start;
    private String tableChargementPilTemp;
    private String currentPhase;
    private Norme norme;
    private String validite;
    private InputStream is;

    // temporary table where data will be loaded by the XML SAX engine
    private String tableTempA = "A";
    private ArrayList<String> tempTableAColumnsLongName=new ArrayList<String>(Arrays.asList("id_source","id","date_integration","id_norme","periodicite","validite"));
    private ArrayList<String> tempTableAColumnsShortName=new ArrayList<String>(Arrays.asList("m0","m1","m2","m3","m4","m5"));
    private ArrayList<String> tempTableAColumnsType=new ArrayList<String>(Arrays.asList("text collate \"C\"","int","text collate \"C\"","text collate \"C\"","text collate \"C\"","text collate \"C\""));


    public ChargeurXml(ThreadLoadService threadChargementService, FilesInputStreamLoad filesInputStreamLoad) {
	this.fileName = threadChargementService.getIdSource();
	this.connection = threadChargementService.getConnection();
	this.tableChargementPilTemp = threadChargementService.getTablePilTempThread();
	this.currentPhase = threadChargementService.getTokenInputPhaseName();
	this.is = filesInputStreamLoad.getTmpInxLoad();
	this.norme = threadChargementService.getNormeFile();
	this.validite = threadChargementService.getValidite();
    }

    @Override
    public void initialisation() {
	LoggerDispatcher.info("** requeteCreateA **", LOGGER);

	java.util.Date beginDate = new java.util.Date();

	StringBuilder request = new StringBuilder();
	request.append(FormatSQL.dropTable(this.tableTempA));
	request.append("CREATE ");

	if (!this.tableTempA.contains(".")) {
	    request.append("TEMPORARY ");
	} else {
	    request.append(" ");
	}

	request.append(" TABLE " + this.tableTempA + " (");
    boolean noComma=true;
    for (int i=0;i<tempTableAColumnsLongName.size();i++)
    {
    	if (noComma)
    	{
    		noComma=false;
    	}
    	else
    	{
    		request.append(",");
    	}
    	request.append(tempTableAColumnsShortName.get(i)+" "+tempTableAColumnsType.get(i)+" ");
    }
    request.append(") ");
    request.append(FormatSQL.WITH_NO_VACUUM);
    request.append(";");

	try {
	    UtilitaireDao.get("arc").executeBlock(this.connection, request);
	} catch (SQLException e) {
	    LoggerDispatcher.error("Error in initialisation table xml loader", LOGGER);
	}
	java.util.Date endDate = new java.util.Date();

	LoggerDispatcher.info("** requeteCreateA en " + (endDate.getTime() - beginDate.getTime()) + " ms **", LOGGER);

    }

    @Override
    public void finalisation() {
	LoggerDispatcher.debug("nothing done", LOGGER);

    }

    @Override
    public void excecution() throws SAXException, IOException, ParserConfigurationException, SQLException {
	LoggerDispatcher.info("** excecution**", LOGGER);
	java.util.Date beginDate = new java.util.Date();
	StringBuilder requeteBilan = new StringBuilder();

	for (String key : col.keySet()) {
	    col.put(key, 1);
	}

	SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
	// Securing the parsing by disallowing the use of a DTD
	saxParserFactory.setFeature(XmlConstants.FEATURE_DISALLOW_DTD, true);
	XMLHandlerCharger4 handler = handlerCreationAndConfiguration();



	XMLParserSecurityUtils.parserSecurityConfiguration(saxParserFactory);
	SAXParser saxParser = saxParserFactory.newSAXParser();
	saxParser.parse(is, handler);

	if (norme.getRegleChargement().getTypeChargement().equals(TypeChargement.PLAT)) {
	    LoggerHelper.debug(LOGGER, "no sql join");
	    requeteBilan.append(AbstractPhaseService.pilotageMarkIdsource(this.tableChargementPilTemp, fileName,
		    this.currentPhase, TraitementState.OK.toString(), null));
	} else {
	    requeteBilan.append(AbstractPhaseService.pilotageMarkIdsource(this.tableChargementPilTemp, fileName,
		    this.currentPhase, TraitementState.OK.toString(), null, handler.jointure));
	}

	 UtilitaireDao.get("arc").executeBlock(this.connection, requeteBilan);
	
	java.util.Date endDate = new java.util.Date();
	LoggerDispatcher.info("** process time" + (endDate.getTime() - beginDate.getTime()) + " ms", LOGGER);

    }



    private XMLHandlerCharger4 handlerCreationAndConfiguration() {
	XMLHandlerCharger4 handler = new XMLHandlerCharger4();

	handler.fileName = fileName;
	handler.connexion = connection;
	handler.col = col;
	handler.colData = colData;
	handler.allCols = allCols;
	handler.requete = requeteInsert;
	handler.tempTableA = this.tableTempA;
	handler.start = 0;
	handler.sizeLimit = 0;
	handler.normeCourante = norme;
	handler.validite = validite;
	handler.tempTableAColumnsLongName=this.tempTableAColumnsLongName;
    handler.tempTableAColumnsShortName=this.tempTableAColumnsShortName;
	return handler;
    }

    @Override
    public void charger() throws SAXException, IOException, ParserConfigurationException, SQLException {
	initialisation();
	excecution();
	finalisation();

    }

    public InputStream getIs() {
	return is;
    }

    /**
     * @param f
     *            the f to set
     */
    public void setIs(InputStream is) {
	this.is = is;
    }

    public int getStart() {
	return start;
    }

    public void setStart(int start) {
	this.start = start;
    }

}
