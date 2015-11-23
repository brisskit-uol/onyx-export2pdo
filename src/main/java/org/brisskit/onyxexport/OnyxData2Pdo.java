package org.brisskit.onyxexport ;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlDateTime;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

import org.brisskit.export.metadata.config.beans.*;

import org.brisskit.onyxdata.beans.ValueSetDocument;
import org.brisskit.onyxdata.beans.ValueSetType;
import org.brisskit.onyxdata.beans.ValueType;
import org.brisskit.onyxdata.beans.VariableValueType;
import org.brisskit.onyxentities.beans.EntitiesDocument;
import org.brisskit.onyxentities.beans.EntryType;
import org.brisskit.onyxmetadata.stagetwo.beans.ContainerDocument;
import org.brisskit.onyxmetadata.stagetwo.beans.Folder;
import org.brisskit.onyxmetadata.stagetwo.beans.Type;
import org.brisskit.onyxmetadata.stagetwo.beans.Variable;
import org.brisskit.onyxmetadata.stagetwo.enumeratedconcept.beans.EnumeratedVariableDocument;
import org.brisskit.onyxmetadata.stagetwo.enumeratedconcept.beans.RevVariable;
import org.brisskit.pdo.beans.EidSetDocument.EidSet;
import org.brisskit.pdo.beans.EventSetDocument.EventSet;
import org.brisskit.pdo.beans.EventType;
import org.brisskit.pdo.beans.ObservationSetDocument.ObservationSet;
import org.brisskit.pdo.beans.ObservationType;
import org.brisskit.pdo.beans.ObservationType.ConceptCd;
import org.brisskit.pdo.beans.ObservationType.EventId;
import org.brisskit.pdo.beans.ObservationType.LocationCd;
import org.brisskit.pdo.beans.ObservationType.ModifierCd;
import org.brisskit.pdo.beans.ObservationType.NvalNum;
import org.brisskit.pdo.beans.ObservationType.ObserverCd;
import org.brisskit.pdo.beans.ParamType;
import org.brisskit.pdo.beans.PatientDataDocument;
import org.brisskit.pdo.beans.PatientDataType;
import org.brisskit.pdo.beans.PatientIdType;
import org.brisskit.pdo.beans.PatientSetDocument.PatientSet;
import org.brisskit.pdo.beans.PatientType;
import org.brisskit.pdo.beans.PidSetDocument.PidSet;

/**
 * The <code>OnyxData2Pdo</code> class represents the process of converting participant data from an Onyx export 
 * file into one or more i2b2 PDO's (Patient Data Object). The latter can then be loaded into the i2b2 data warehouse
 * either by XSLT transformations into SQL insert commands, or by loading using an i2b2 web service.
 * <p/>
 * Notes:<br/>
 * 1. The process can be invoked to produce "test" data from an Onyx export file.<br/>
 *    For test data generation:<br/>
 *    a. Date references are altered.<br/>
 *    b. Any numeric data (eg: age) is altered by a small amount, hopefully leaving the value still sensible.<br/>
 *    c. Enrollment id is completely generated (ie: the original is ignored)<br/>
 *    d. Every 5th participant is duplicated in its entirety (then randomized as per a,b and c above).<br/>
 *       This is an attempt at altering proportions in a way to make marginal tracing more difficult.<br/>
 *    Please see method generateTestAdjustments() for the algorithm for adjusting dates and numerics. 
 * <p/>
 * The process is currently a command line invocation. See section <a href="OnyxData2Pdo.html#usage">Usage</a>
 * 
 * @author  Jeff Lusted jl99@le.ac.uk
 *
 */
public abstract class OnyxData2Pdo {
	
	private static Log log = LogFactory.getLog( OnyxData2Pdo.class ) ;
	
	/**
	 * The process is currently a command line invocation with the following usage:
	 * <p><blockquote><pre>
	 * Usage: OnyxData2Pdo {Parameters}    
     * Parameters:
     *  -export=path to onyx export directory
     *  -refine=path to main refined metadata-directory
     *  -enum=path to main refined enum directory
     *  -ontology=nominal/real
     *  -config=path-to-xml-config-file
     *  -pdo=path to pdo output directory
     *  -name=refined metadata file name
     *  -batch=maximum number of participants per pdo file
     *  -test=yes/no
     *  -bid=aaaa-nnnnn
     *  -pid=nnnnn
     *  -eid=nnnnn
     * Notes:
     *  (1) The export, refine, enum, config, pdo and name parameters are mandatory.
     *  (2) The export directory must exist and contain an unzipped Onyx export file.
     *      This directory must have had the namespace replacement script run against it
     *      to ensure all xml files have a suitable namespace in place.
     *  (3) The refine meta directory must contain the main refined meta data file.
     *  (4) The enum directory must contain a complete set of meta data files for enumerated concepts.
     *  (5) The ontology must be either "nominal" or "real". Defaults to "real".
     *  (6) The config file must exist.
     *  (7) The pdo directory must not exist.
     *  (8) The name parameter refers to the name of the main refined metadata file.
     *      Usually something like 1-Refined-Metadata.xml.
     *  (9) If not entered, the batch parameter defaults to 10.
     *  (10) The test parameter defaults to 'no'.
     *      -test=yes will produce files with the string \"TEST-DATA-ONLY\" in the file name.
     *  (11) The presence or absence of the pid and eid parameters is important in switching
     *      the working mode of the programme. The pid / eid parameters must either be present as
     *      a pair, or completely absent. If present, this will produce a PDO file with the
     *      i2b2 internal identifiers for patients and events already set within the PDO:
     *      this is an extremely limiting factor, for which see notes (10) and (11).
     *      If the pair is omitted, the programme assumes the PDO will be processed by the
     *      crc loader, which will itself assign appropriate internal identifiers.
     *  (12) The pid parameter refers to the starting number for patient identifiers.
     *      If present, the value must be a positive integer. 
     *      It is your responsibility to know what the next new patient identifier should be!
     *  (13) The eid parameter refers to the starting number for event identifiers.
     *      If present, the value must be a positive integer. 
     *      It is your responsibility to know what the next new event identifier should be!
     *  (14) The bid parameter is only required when generating test data (see note 10 above).
     *      The value is used to generate a meaningless substitute for the enrollment id.
     *      The format should be aaaa-nnnnn. The aaaa value is used as a prefix.
     *      The nnnnn value is used as an integer which is incremented for each new participant.
     *      For example, with -bid=demo-1, the enrollment id's will be a series starting with:
     *      demo-000000001
     *      demo-000000002
     *      demo-000000003 and so on.
     * </pre></blockquote><p> 
	 */
	private static final String USAGE =
        "Usage: OnyxData2Pdo {Parameters}\n" +       
        "Parameters:\n" +
        " -export=path to onyx export directory\n" +
        " -refine=path to main refined metadata-directory\n" +
        " -enum=path to main refined enum directory\n" +
        " -ontology=nominal/real\n" +
        " -config=path-to-xml-config-file\n" +
        " -pdo=path to pdo output directory\n" +
        " -name=refined metadata file name\n" +
        " -batch=maximum number of participants per pdo file\n" +
        " -test=yes/no\n" +
        " -bid=aaaa-nnnnn\n" +
        " -pid=nnnnn\n" +
        " -eid=nnnnn\n" +
        "Notes:\n" +
        " (1) The export, refine, enum, pdo and name parameters are mandatory.\n" +
        " (2) The export directory must exist and contain an unzipped Onyx export file.\n" +
        "     This directory must have had the namespace replacement script run against it\n" +
        "     to ensure all xml files have a suitable namespace in place.\n" +
        " (3) The refine meta directory must contain the main refined meta data file.\n" +
        " (4) The enum directory must contain a complete set of meta data files for enumerated concepts.\n" +
        " (5) The ontology must be either \"nominal\" or \"real\". Defaults to \"real\".\n" +
        " (6) The config file must exist.\n" +
        " (7) The pdo directory must not exist.\n" +
        " (8) The name parameter refers to the name of the main refined metadata file.\n" +
        "     Usually something like 1-Refined-Metadata.xml.\n" +
        " (9) If not entered, the batch parameter defaults to 10.\n" +
        " (10) The test parameter defaults to 'no'.\n" +
        "     -test=yes will produce files with the string \"TEST-DATA-ONLY\" in the file name.\n" +
        " (11) The presence or absence of the pid and eid parameters is important in switching\n" +
        "     the working mode of the programme. The pid / eid parameters must either be present as\n" +
        "     a pair, or completely absent. If present, a PDO file will be produced with the\n" +
        "     i2b2 internal identifiers for patients and events already set within the PDO:\n" +
        "     this is an extremely limiting factor, for which see notes (10) and (11).\n" +
        "     If the pair is omitted, the programme assumes the PDO will be processed by the\n" +
        "     crc loader, which will itself assign appropriate internal identifiers.\n" +
        " (12) The pid parameter refers to the starting number for patient identifiers.\n" +
        "     It must be a positive integer. If not entered, defaults to 1.\n" +
        "     It is your responsibility to know what the next new patient identifier should be!\n" +
        " (13) The eid parameter refers to the starting number for event identifiers.\n" +
        "     It must be a positive integer. If not entered, defaults to 1.\n" +
        "     It is your responsibility to know what the next new event identifier should be!\n" + 
        " (14) The bid parameter is only required when generating test data (see note 10 above).\n" +
        "      The value is used to generate a meaningless substitute for the enrollment id.\n" +
        "      The format should be aaaa-nnnnn. The aaaa value is used as a prefix.\n" +
        "      The nnnnn value is used as an integer which is incremented for each new participant.\n" +
        "      For example, with -bid=demo-1, the enrollment id's will be a series starting with:\n" +
        "      demo-000000001\n" +
        "      demo-000000002\n" +
        "      demo-000000003 and so on." ;
		
//	private static final String[] INTERVENTIONS_THIS_CLINICAL_EPISODE =
//	{ 
//		"epi_intcabg", "epi_intvalve", "epi_inttavi", "epi_intppci",
//		"epi_intopci", "epi_intpace", "epi_inticd", "epi_intlvad", 
//		"epi_intthromb", "epi_intablat", "epi_testangio" 
//	} ;
	
	private static final String[] SPONGE_CATEGORIES = {
		"OTHER", "NOT STATED"
	} ;
	
	private static String exportDir = null ;	
	private static String pdoDir = null ;	
	private static String refineDir = null ;
	private static String enumDir = null ;
	private static String configFilePath = null ;
	private static boolean includePatientDim = true ;
	private static String mainMetadataFileName = null ;
	private static String ontologyType = null ;
	private static int batchSize = 10 ;
	
	protected static boolean testFlag = false ;
	private static String idPrefix = null ;
	private static int idNum = -1 ;
	
	
	private static File exportDirectory ;
	private static File i2b2DataDirectory ;
	private static File refineDirectory ;
	private static File enumDirectory ;
	private static File configFile ;
	private static File mainMetadataFile ;
	
	public static final String HIVE = "HIVE" ;
	
	private static final String TIMEZONE_REGEX = ".*[+|-]\\d{4}$" ;
	
	private static final String FRAGMENT_START = "<xml-fragment valueType=\"datetime\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" ;
	private static final String FRAGMENT_END = "</xml-fragment>" ;
	
	protected ContainerDocument metadataDoc ;
	protected HashMap<String,XmlObject> ontologicalVariables ;
	
	private LinkedHashMap<String, String> participants ;
	private ValueSetDocument[] dataDocs ;
	private PatientDataDocument pdoDoc ;
	
	protected OnyxExportConfigDocument configDoc ;
	protected PdoPhaseType pdoPhase ;
	protected boolean bNominalOntology ;
	protected String systemAcroymn ;
	protected String projectAcronymn ;
	
	protected String nominalCodePrefix ;
	
	protected IExport2Pdo userDefinedProcess ;
	
	//
	// The user must be prepared to supply these if the web service route is not used...
	private static int patientNum = -1 ;
	private static int encounterNum = -1 ;
	//
	// Alternative to the above, as the crc loader will allocate internal identifiers
	private static boolean useWebService = true ;
	
	//
	//
	protected String participantId ;
	protected String enrollmentId ;
	
	private ArrayList<TestParticipantHolder> testParticipants = null ;
	
	//
	//
	protected Calendar adminCalendar ;
	
	//
	//
	int testYearAdjustment = 0 ;
	int testMonth = 0 ;
	int testDay = 0 ;
	int testNumericsAdjustment = 0 ;
	
	private static StringBuffer logIndent = null ;
		
	/**
	 * Invokes the principle process:
     * <p><blockquote><pre>
     *    1. Vets the input parameters.
     *    2. Executes the principle process.
     * </pre></blockquote><p>
     * Vetting and construction of the main process object is delegated to a factory object.
     * 
	 * @param args  See section <a href="OnyxData2Pdo.html#USAGE">Usage</a>
	 */
	public static void main( String[] args ) {		
		//
		// Retrieve command line arguments...
		boolean good = Factory.retrieveArgs( args ) ;		
		if( !good ) {
			System.out.println( USAGE ) ; 
			System.exit(1) ;
		}
		//
		// Do some other basic checks against the command line arguments...
		try {
			Factory.levelTwoChecksAgainstCommandLineArgs() ;
		}
		catch( FactoryException fex ) {
			System.out.println( fex.getLocalizedMessage() + "\n" ) ;
			System.out.println( USAGE ) ; 
			System.exit(1) ;
		}
		
		try {						
			OnyxData2Pdo od2bpdo = Factory.newInstance() ;
			od2bpdo.exec() ;
		}
		catch( Exception ex ) {
			ex.printStackTrace() ;
			System.exit( 1 ) ;
		}

		System.out.println( "Done!" ) ;	
		System.exit( 0 ) ;
	}
	
	/**
	 * Represents the main workflow control.<p/>
	 * For each participant:
	 * <p><pre>
	 * 1. Retrieves a set of data documents.
	 * 2. If a test run, sets up randomized adjustments to be applied to dates / years etc.
	 * 3. Builds a PDO (Patient Data Object).
	 * </pre><p>
	 * A PDO can hold data for more than one participant. This routine controls the "batching"
	 * of these; ie: how many participants are included in each PDO file. This can be controlled
	 * by one of the input parameters.
	 * 
	 * @throws ProcessException
	 */
	public void exec() throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "exec()" ) ;
		try {
			//
			// Process one participant at a time ...
			Iterator<String> it = this.participants.keySet().iterator() ;
			int processedCount = 0 ;
			int countForDuplicationPurposes = 0 ;
			while( it.hasNext() ) {
				this.participantId = it.next() ;
				String participantFile = participants.get( participantId ) ;
				log.debug( "Processing " + participantId + " using file set " + participantFile ) ;
				primeDataDocs() ;
				//
				// Make necessary adjustments for a run producing test data only...
				if( OnyxData2Pdo.testFlag == true ) {
				    //
				    // Following sets up adjustment figures for test data dates...
				    generateTestAdjustments() ;  						 
				}				
				buildPatientDataObject() ;
				processedCount++ ;
				countForDuplicationPurposes++ ;
				if( processedCount == batchSize ) {
					savePatientDataObject( processedCount ) ;
					processedCount = 0 ;
				}
				//
				// Experiment to throw up misleading proportions for test data generation.
				// Every fifth participant we duplicate.
				// Just another level of obfuscation.
				if( OnyxData2Pdo.testFlag == true && countForDuplicationPurposes%5 == 0 ) {
				    //
				    // Following sets up adjustment figures for test data dates...
				    generateTestAdjustments() ; 
				    //
				    // Duplicate the last participant...
				    buildPatientDataObject() ;
					processedCount++ ;
					countForDuplicationPurposes++ ;
					if( processedCount == batchSize ) {
						savePatientDataObject( processedCount ) ;
						processedCount = 0 ;
					}
				}				
				
			}
			if( processedCount > 0 ) {
				savePatientDataObject( processedCount ) ;
			}
			if( this.testFlag ) {
				outputTestDataLog() ;
			}
			
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "exec()" ) ;
		}		
	}
	
	private void generateTestAdjustments() {
		Random r = new Random() ;
	    this.testYearAdjustment = r.nextInt( 10 ) + 1 ;
	    this.testMonth = r.nextInt( 12 ) ;
	    this.testDay = r.nextInt( 28 ) + 1 ; 
	    //
	    // Numerics (decimals or integers) can be anything;
	    // eg: perhaps an age or perhaps a year or
	    // perhaps a measurement.
	    // We make a small non-zero random adjustment
	    // to make a difference but hopefully not enough 
	    // to make the measure nonsensical...
	    this.testNumericsAdjustment = 0 ;
	    while( this.testNumericsAdjustment == 0 ) {
	    	this.testNumericsAdjustment = r.nextInt( 2 ) + 1 ;
	    }
	}
	
	private void outputTestDataLog() throws ProcessException {
		String fullPath = null ;
		try {
			//
			// Establish a suitable report file.
			// In the same directory as the PDO's seems sensible...
			StringBuilder b = new StringBuilder() ;
			b.append( i2b2DataDirectory.getAbsolutePath() )
			 .append( System.getProperty( "file.separator" ) ) 
			 .append( "testdatalog.txt" ) ;		
			fullPath = b.toString() ;
			File reportFile = new File( fullPath ) ;
			PrintWriter writer = new PrintWriter( reportFile ) ;
			
			//
			// Write out a header
			writer.append( "*===================================================*\n" ) ;
			writer.append( "*       Test Participant Details                    *\n" ) ;
			writer.append( "*===================================================*\n" ) ;
			
			//
			// Write a simple line for each test participant processed...
			Iterator<TestParticipantHolder> it = this.testParticipants.iterator() ;
			while( it.hasNext() ) {
				writer.append( it.next().toString() + "\n" ) ;
			}
			
			writer.close() ;
		}
		catch( FileNotFoundException fnfx ) {
			throw new ProcessException( "Could not create file: " + fullPath, fnfx ) ;
		}
		
	}
	
	/**
	 * Collects together the data files for one participant.
	 * 
	 * @throws ProcessException
	 */
	private void primeDataDocs() throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "primeDataDocs()" ) ;
		String fileName = this.participants.get( participantId ) ;

		ArrayList<ValueSetDocument> aldd = new ArrayList<ValueSetDocument>() ;
		//
		// Search export directories for given file name...
		File[] children = exportDirectory.listFiles() ; 
		try {
			for( int i=0; i<children.length; i++ ) {
				if( children[i].isDirectory() ) {
					File[] files = children[i].listFiles() ;
					for( int j=0; j<files.length; j++ ) {
						if( files[j].getName().equalsIgnoreCase( fileName ) ) {
							ValueSetDocument vsDoc = null ;
							try {
								vsDoc = ValueSetDocument.Factory.parse( files[j] ) ;
								log.debug( "Parsed file; " + files[j].getAbsolutePath() ) ;
								if( !participantId.equals( vsDoc.getValueSet().getEntityIdentifier() ) ) {
									throw new ProcessException( "Mismatch on participant id. Target expected: " +
											participantId + " but found: " + vsDoc.getValueSet().getEntityIdentifier() ) ;
								}
								aldd.add( vsDoc ) ;
							}
							catch( IOException iox ) {
								throw new ProcessException( "Something wrong with data file", iox ) ;
							}
							catch( XmlException xmlx ) {
								throw new ProcessException( "Could not parse data file.", xmlx ) ;   			
							}
							break ;
						}
					}
				}
			}
			this.dataDocs = aldd.toArray( new ValueSetDocument[ aldd.size() ] ) ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "primeDataDocs()" ) ;
		}		
	}
	
	/**
	 * Builds the PDO details for the given participant. <p/>
	 * 
	 * It is probably more intuitive to think of a PDO being built for each participant,
	 * but the fact is one PDO can hold a number of participants. <p/>
	 * 
	 * NB: The patient dimension can be excluded if the patient dimension information
	 * is provided by another system (eg: civiCRM).
	 * 
	 * This is the order: <br/>
	 * 1. Patient Mapping <br/>
	 * 2. Patient Dimension <br/>
	 * 3. Visit Mapping <br/>
	 * 4. Visit Dimension <br/>
	 * 5. Observation Facts 
	 * 
	 * @throws ProcessException
	 */
	private void buildPatientDataObject() throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "buidPatientDataObject()" ) ;
		try {
			this.enrollmentId = null ;
			//
			// If this is a test data extraction run, save up some data for 
			// helpful logging messages to be output at the end of the run ...
			if( OnyxData2Pdo.testFlag ) {
				TestParticipantHolder tph = 
						new TestParticipantHolder( getEnrollmentId()
								                 , getGender() 
								                 , getBirthDate() ) ;
				this.testParticipants.add( tph ) ;
			}
						
			buildPatientMapping() ;
			//
			// The patient dimension can be included/excluded by input parameter...
			if( includePatientDim ) {
				buildPatientDimension() ;
			}
			buildVisitMapping() ;
			buildVisitDimension() ;
			buildObservations() ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buidPatientDataObject()" ) ;
		}
	}
	
	/**
	 * Lazy initialization procedure for getting the top level 
	 * PatientDataType element in the PDO.
	 * 
	 * @return the top level PatientDataType
	 */
	private PatientDataType getPatientDataType() {
		if( pdoDoc == null ) {
			pdoDoc = PatientDataDocument.Factory.newInstance() ;
		}
		if( pdoDoc.getPatientData() == null ) {
			pdoDoc.addNewPatientData() ;
		}
		return pdoDoc.getPatientData() ;
	}
	
	/**
	 * Lazy initialization procedure for getting the one and only ObservationSet
	 * within a PDO. 
	 * 
	 * NOTE: this forces the PDO to contain one ObservationSet however many patients 
	 * are held within the PDO. The schema allows one per patient.
	 * 
	 * @return the ObservationSet
	 */
	private ObservationSet getObservationSet() {
		ObservationSet[] osa = getPatientDataType().getObservationSetArray() ;
		if( osa != null ) {
			if( osa.length == 0 ) {
				return getPatientDataType().addNewObservationSet() ;
			}
			return osa[0] ;
		}
		return getPatientDataType().addNewObservationSet() ;
	}
	
	/**
	 * Lazy initialization procedure for the PDO's Patient Mapping set. <br/>
	 * (Also known as the PID set, as it maps patient identifiers).
	 * 
	 * @return the Patient Mapping set.
	 */
	protected PidSet getPatientMappingSet() {
		PatientDataType pdt = getPatientDataType() ;
		if( pdt.getPidSet() == null ) {
			return pdt.addNewPidSet() ;
		}
		return pdt.getPidSet() ;
	}
	
	/**
	 * Lazy initialization procedure for the PDO's Visit Mapping set. <br/>
	 * (Also known as the EID set, as it maps event identifiers).
	 * 
	 * @return the Visit Mapping set.
	 */
	protected EidSet getVisitMappingSet() {
		PatientDataType pdt = getPatientDataType() ;
		if( pdt.getEidSet() == null ) {
			return pdt.addNewEidSet() ;
		}
		return pdt.getEidSet() ;
	}
	
	/**
	 * Builds the Visit Mapping. <br/>
	 * (Also known as the Encounter Mapping or the EID set). <p/>
	 * 
	 */
	protected abstract void buildVisitMapping() ;
	
	/**
	 * Builds the Patient Mapping. <p/>
	 * 
	 * 
	 */
	protected abstract void buildPatientMapping() ;
	
	
	/**
	 * @param xo
	 * @return the text value of the given XML element 
	 */
	protected String getText( XmlObject xo ) {
		XmlCursor cursor = xo.newCursor() ;
		try {
			return cursor.getTextValue().trim() ;
		}
		finally {
			cursor.dispose() ;
		}
	}
	
	/**
	 * Lazy initialization procedure for the PDO's Patient Dimension set. <br/>
	 * (Also known as the patient set).
	 * 
	 * @return the Patient Dimension set.
	 */
	private PatientSet getPatientDimensionSet() {
		PatientDataType pdt = getPatientDataType() ;
		if( pdt.getPatientSet() == null ) {
			return pdt.addNewPatientSet() ;
		}
		return pdt.getPatientSet() ;
	}
	
	/**
	 * Builds the Patient Dimension. <p/>
	 * 
	 * <b>Notes</b>:
	 * <p><pre>
	 * 1. Uses the patient number. 
	 *    Again: how would this be acquired and coordinated
	 *    in a procedure that would be based upon a web service?
	 * 2. Is this an adequate choice of data to be folded into the Patient Dimension?... 
	 *    Birthdate
	 *    Age in years
	 *    Ethnicity
	 *    Gender
	 *    Recruitment code
	 *    Enrollment Id (Hospital s-Number)
	 *    BRICCS Participant Id
	 * 3. What about the admin group of dates?
	 * 4. What about Source System Id and Upload Id?
	 * </pre><p>
	 * 
	 */
	private void buildPatientDimension() {
		if( log.isTraceEnabled() ) enterTrace( "buildPatientDimension()" ) ;
		try {
			PatientType pt = getPatientDimensionSet().addNewPatient() ;
			pt.addNewPatientId().setStringValue( getCurrentPatientNum() ) ;
			pt.getPatientId().setSource( getCurrentPatientSource() ) ;
			
			IncludeType[] includes = configDoc.getOnyxExportConfig().getPdoPhase().getPatientDimension().getIncludeArray() ;
			//
			// There may be multiple questionnaire parts (stages or entities) that contribute.
			// The outer loop deals with each of these from the configuration file...
			for( int i=0; i<includes.length; i++ ) {
				//
				// Each entity or stage may contribute more than one value / column.
				// The inner loop deals with each of these from the configuration file...
				PatientDimensionColumnType[] pdcta = includes[i].getPatientDimensionColumnArray() ;
				for( int j=0; j<pdcta.length; j++ ) {
					//
					// If there is no column name present, then this cannot be included in the patient dimension ...
					if( pdcta[j].isSetColumn() == false ) {
						continue ;
					}
					//
					// If a default value exists, use that straight away...
					if( pdcta[j].isSetDefaultValue() ) {
						setParam( pt, pdcta[j].getColumn(), pdcta[j].getName(), pdcta[j].getDefaultValue() ) ;
					}
					else {
						//
						// Retrieve what value we have...
						ValueType value = getValueAsXmlObject( includes[i].getQuestionnaire(), pdcta[j].getVariable() ) ;
						//
						// If we don't have a value for this participant, log the fact ...
						if( value == null ) {
							log.warn( "Missing value. Could not build PatientDimension column: " + pdcta[j].getColumn() ) ;
							continue ;
						}
						//
						// Then deal with each type appropriately.
						// (How many types should I consider dealing with?)...
						String valueType = value.getValueType() ;
						//
						// This covers the obvious Birthday, 
						// which in test situations requires some "random" adjustment...
						if( valueType.equalsIgnoreCase( "DATETIME" ) || valueType.equalsIgnoreCase( "DATE" )) {
							String dateTime = getDateTime( value ) ;
							setParam( pt, pdcta[j].getColumn(), pdcta[j].getName(), dateTime ) ;
						}
						//
						// Text, including AGE (?)
						else if( valueType.equalsIgnoreCase( "TEXT" ) ) {
							if( pdcta[j].getName().equalsIgnoreCase( "AGE" ) ) {
								String age = getAge( value ) ;
								setParam( pt, pdcta[j].getColumn(), pdcta[j].getName(), age ) ;
							}
							else {
								setParam( pt, pdcta[j].getColumn(), pdcta[j].getName(), getText( value ) ) ;
							}
						}
						//
						// Integer, including AGE (?)
						else if( valueType.equalsIgnoreCase( "INTEGER" ) ) {
							if( pdcta[j].getName().equalsIgnoreCase( "AGE" ) ) {
								String age = getAge( value ) ;
								setParam( pt, pdcta[j].getColumn(), pdcta[j].getName(), age ) ;
							}
							else {
								setParam( pt, pdcta[j].getColumn(), pdcta[j].getName(), getText( value ) ) ;
							}
						}
					}
					
				} // inner for loop
				
			} // outer for loop
			//
			// Set up admin values...
			pt.setUpdateDate( adminCalendar ) ;
			pt.setDownloadDate( adminCalendar ) ;
			pt.setImportDate( adminCalendar ) ;
			pt.setSourcesystemCd( this.projectAcronymn ) ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildPatientDimension()" ) ;
		}
	}
	
	/**
	 * Utility function for setting parameters within the Patient Dimension.
	 * 
	 * @param patientType
	 * @param column
	 * @param name
	 * @param value
	 */
	private void setParam( PatientType patientType, String column, String name, String value ) {
		ParamType param = patientType.addNewParam() ;
		param.setColumn( column ) ;
		param.setName( name ) ;
		param.setStringValue( value ) ;
	}
	
	/**
	 * Lazy initialization procedure for the PDO's Visit Dimension set. <br/>
	 * (Also known as the event set).
	 * 
	 * @return The Visit Dimension set.
	 */
	private EventSet getVisitDimensionSet() {
		PatientDataType pdt = getPatientDataType() ;
		if( pdt.getEventSet() == null ) {
			return pdt.addNewEventSet() ;
		}
		return pdt.getEventSet() ;
	}
	
	/**
     * Builds the Visit Dimension. <p/>
	 * 
	 * <b>Notes</b>:
	 * <p><pre>
	 * 1. Uses the patient number. Also uses a newly incremented encounter number.
	 *    Again: how would these be acquired and coordinated
	 *    in a procedure that would be based upon a web service?
	 * 2. Start date is: Admin.Participant.captureStartDate
	 * 3. End date is: Admin.Participant.captureEndDate
	 * 4. The inout_cd, location_cd and location_path are set as missing values
	 * 5. What about the admin group of dates?
	 * 6. What about Source System Id and Upload Id?
	 * </pre><p>
	 */
	private void buildVisitDimension() { 
		if( log.isTraceEnabled() ) enterTrace( "buildVisitDimension()" ) ;
		try {
			EventType et = getVisitDimensionSet().addNewEvent() ;
			et.addNewEventId().setStringValue( getCurrentEncounterNum() ) ;
			et.getEventId().setSource( getCurrentEncounterSource() ) ;
			et.addNewPatientId().setStringValue( getCurrentPatientNum() ) ;
			et.getPatientId().setSource( getCurrentPatientSource() ) ;
			//
			// Set active status...
			setParam( et, "ACTIVE_STATUS_CD", "active status", "F" ) ; 
			//
			// Locate driving entity type ( for Briccs, this is Participant )
			// and retrieve default start and end dates variable names.
			// We accommodate possible alternative names (eg: Participants or Participant)...
			//
			String valueSetName = null ;
			String alternateValueSetName = null ;
			String startDateVariableName = null ;
			String endDateVariableName = null ;
			DateContextType[] qta = configDoc.getOnyxExportConfig().getPdoPhase().getDefaultObservationDates().getDateContextArray() ;
			for (DateContextType dateContextType : qta) {
				if( dateContextType.getDrivingEntity() ) {
					valueSetName = dateContextType.getQuestionnaire() ;
					if( dateContextType.isSetAlternateName() ) {
						alternateValueSetName = dateContextType.getAlternateName() ;
					}
					startDateVariableName = dateContextType.getStartdate().getVariable() ;
					endDateVariableName = dateContextType.getEnddate().getVariable() ;
				}
			}
			
			//
			// Set start date...
			XmlObject xoStartDateTime = getValueAsXmlObject( valueSetName, startDateVariableName ) ;
			if( xoStartDateTime == null ) {
				xoStartDateTime = getValueAsXmlObject( alternateValueSetName, startDateVariableName ) ;
			}
			Calendar startDateTime = getCorrectedDateTimeValue( xoStartDateTime ) ;
			if( startDateTime != null ) {
				if( OnyxData2Pdo.testFlag == true ) {
					startDateTime = adjustCalendarForTestRun( startDateTime ) ;
				}
				et.setStartDate( startDateTime ) ;
			}
			else {
				log.error( "Visit Dimension start date missing." ) ;
			}
			//
			// Set end date...
			XmlObject xoEndDateTime = getValueAsXmlObject( valueSetName, endDateVariableName ) ;
			if( xoEndDateTime == null ) {
				xoEndDateTime = getValueAsXmlObject( alternateValueSetName, endDateVariableName ) ;
			}
			Calendar endDateTime = getCorrectedDateTimeValue( xoEndDateTime ) ;
			if( endDateTime != null ) {
				if( OnyxData2Pdo.testFlag == true ) {
					endDateTime = adjustCalendarForTestRun( endDateTime ) ;
				}
				et.setEndDate( endDateTime ) ;
			}
			else {
				log.error( "Visi Dimension end date missing." ) ;
			}

			//
			// Set inout_cd, location_cd and location_path as missing values...
			setParam( et, "INOUT_CD", "", "@" ) ; 
			setParam( et, "LOCATION_CD", "", "@" ) ;
			setParam( et, "LOCATION_PATH", "", "@" ) ;;
			//
			// Set admin group...
			et.setUpdateDate( adminCalendar ) ;
			et.setDownloadDate( adminCalendar ) ;
			et.setImportDate( adminCalendar ) ;
			et.setSourcesystemCd( this.projectAcronymn ) ; 			

		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildVisitDimension()" ) ;
		}
	}
	
	/**
	 * Utility function for setting parameters within the Visit Dimension.
	 * 
	 * @param eventType
	 * @param column
	 * @param name
	 * @param value
	 */
	private void setParam( EventType eventType, String column, String name, String value ) {
		ParamType param = eventType.addNewParam() ;
		param.setColumn( column ) ;
		param.setName( name ) ;
		param.setStringValue( value ) ;
	}
	
	/**
	 * Builds Observation Facts for a participant. <p/>
	 * 
	 * This is the main algorithm for forming observation facts.<p/>
	 * 
	 * <b>NB:</b> The strategy implemented here for Onyx variables is to fall back on
	 * accommodating them as either discrete variables (true/false) or enumerated variables. 
	 * In effect, not variables that can be measured by a continuous measure. 
	 * (This is in contrast to pathology tests, where the <em>background</em> is to expect 
	 * a continuous measure of some sort.)
	 * <p/>
	 * 
	 * <b>For each variable from each file relating to a participant...</b><p/>
	 * 
	 * <b>Firstly</b>, all of the following are <b>ignored</b>:
	 * <p><pre>
	 * 1. All variables from QuestionnaireRun and QuestionnaireMetric.
	 * 2. All variables from Participant except for:
	 *    ethnicity, age, gender and recruitment type.
	 * 3. Other primary diagnoses, other secondary diagnoses and other symptoms
     *    are textual note fields which should get folded into the associated 
     *    "other" observation_fact. But I don't think they are. 
     *    What should I do?
     * 4. Symptoms onset are ignored here and used instead as start date
	 *    for the associated observation_fact.
	 * 5. Patient email 1 and email 2 from the EndContactQuestionnaire.
	 * 6. TubeCode, barcode and prefixCode from UrineSamplesCollection
	 *    and BloodSamplesCollection.
	 * 7. The following Onyx types: DATA, LOCALE and BINARY
	 * </pre><p>  
	 * <b>Secondly</b>, we examine the remainder for BOOLEANS where the answer is TRUE. 
	 * Some of these are (already) enumerated types (ie: not generated ones, but ones
	 * designed into the questionnaire itself), and are definite observation facts, 
	 * whilst others have an uncertain status. The only way to decide between the two 
	 * is to see whether we have ontological data within the refined metadata. If that exists, 
	 * then we are looking at an enumerated type and the value represents an observation fact. 
	 * These are processed as enumerations. The other BOOLEANS are reported upon.
	 * <p/>		
	 * <b>Thirdly</b>, non BOOLEANS where ontological data can be found are examined. <br/>
	 * It has to be said at the outset that each of these could be considered a discrete 
	 * observation fact, but as emphasized above, the overall strategy (at least as a point 
	 * of departure) has been to accommodate facts as either true/false statements or as 
	 * enumerations of values. <br/>
	 * This is what happens:
	 * <p><pre>
	 * 1. All variables where we have specifically designed for an enumeration are processed.
	 *    Currently, these are of type: 
	 *    
	 *    AGE, BEERNUMBER, BICEPS, CIGARETTENUMBER, CIGARNUMBER, DIASTOLICBP, ETHNICITY,
	 *    HEARTRATE, HEIGHT, HIPS, PIPENUMBER, RELATIVESNUMBER, SMALLNUMBER, SUBSCAPULAR,
	 *    SUPRAILIAC, SYSTOLICBP, TRICEPS, WAIST, WEIGHT, WINESPIRITNUMBER, YEAR, RECENTTIME.
	 *    
	 *    Note that these are types, and can therefore account for a considerable number of
	 *    facts (eg: YEAR covers every question where a year can be returned as an answer).
	 *    
	 * 2. For type of DATETIME, if the variable concerns interventions this clinical episode,
	 *    then we build a non-generated enumeration using the datetime value as the 
	 *    observation start date. 
	 *    The other DATETIME variables are reported upon <em>but no facts built</em>. 
	 *    
	 * 3. For types DECIMAL, INTEGER and TEXT 
	 *    variables are reported upon <em>but no facts built</em>.
	 *    
	 * 4. For all others, variables are reported upon <em>but no facts built</em>.
	 * </pre><p>   
	 * <b>Fourthly</b>, non BOOLEANS where ontological data cannot be found are examined. <br/> 
	 * The following are special cases within this group where enumerations are built:
	 * <p><pre>
	 * 1. Admin.Participant.age of type INTEGER          (generated enumeration)
	 * 2. Admin.Participant.pat_ethnicity of type TEXT   (generated enumeration)
	 * 3. Admin.Participant.gender of type TEXT          (built-in enumeration)
	 * 4. Admin.Participant.recruitmentType of type TEXT (built-in enumeration)
	 * 5. Admin.Participant.vital_status of type TEXT    (generated enumeration)
	 * </pre><p>
	 * Other variables within this group are reported upon <em>but no facts built</em>.<p/>
	 * 
	 * <b>Lastly</b>, any other variables are reported upon <em>but no facts built</em>.<p/>
	 * 
	 * @throws ProcessException
	 */
	private void buildObservations() throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "buildObservations()" ) ;
		try {
			//
			// Establish a set to contain observation facts for this participant...
			ObservationSet obset = getObservationSet() ;
			
			//
			// This is probably the best place to add vital_status, which does not
			// exist in the Onyx data, but is implied: the participants are interviewed
			// so we assume their vital status is not deceased!
			//
			// However, I'm uncertain how this would relate to a real ontology
			// or indeed to another project other than BRICCS,
			// so for the moment...
//			if( this.bNominalOntology 
//			    &&
//			    this.projectAcronymn.toUpperCase().contains( "BRICCS") ) {
//				//
//				// I'm not sure this belongs outside of the PatientDimension...
//				buildVitalStatusFact( obset ) ;
//			}			
			//
			// Each participant has a number of data files, 
			// so process each data file in turn...
			for( int i=0; i<this.dataDocs.length; i++ ) {
				//
				// Within each data file, process each variable in turn...
				VariableValueType[] vvta = dataDocs[i].getValueSet().getVariableValueArray() ;
				for( int j=0; j<vvta.length; j++ ) {
					String variableName = vvta[j].getVariable() ;
					//*****************************************************************
					//* This next section aims to eliminate some variables.           *
					//*****************************************************************
					//
					// The following types are Onyx types which we ignore for the moment
					// (But can they be ignored in the future?)...
					String type = vvta[j].getValue().getValueType() ;
					if( type.equalsIgnoreCase( "DATA" )
						||
						type.equalsIgnoreCase( "LOCALE" ) 
						||
						type.equalsIgnoreCase( "BINARY" )  ) {
						continue ;
					}
					
					//*****************************************************************
					//* This next section is where candidate variables are processed. * 
					//*****************************************************************
										
					//
					// BOOLEANS where the answer is TRUE are potential facts.
					// Some of these are already enumerated types (ie: not generated ones),
					// Others are OPEN questions. 
					// The only way to decide between the two is to see whether we have ontological
					// data within the refined metadata. If that exists, then we are looking at
					// an enumerated type and the value represents an observation fact.
					// So if we have ontological data on them, we should process the TRUE ones...
					String valueType = vvta[j].getValue().getValueType() ;
					if( valueType.equalsIgnoreCase( "BOOLEAN" ) ) {
						if( getText( vvta[j].getValue() ).equalsIgnoreCase( "TRUE" ) ) {	
							//
							// Questionnaire variables which are Booleans and "true" can point to...
							// bottom leaves OR folders within a real ontology!
							// (This is ONLY true for a real as opposed to a nominal ontology.)
							// So we search first for a bottom leaf (of type Variable).
							// If that is unsuccessful, we search for a folder (of type Folder)...
							Variable v = getOntologicalVariable( dataDocs[i].getValueSet().getValueTable(), variableName ) ;
							if( v != null ) {
								if( log.isDebugEnabled() ) {
									log.debug( "Observation fact: " + variableName + " with value: TRUE using variable " + v.getName() ) ;
								}						
								buildFact_NonGeneratedEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], v.getCode() ) ;
							}
							else {
								Folder f = getOntologicalFolder( dataDocs[i].getValueSet().getValueTable(), variableName ) ;
															
								if( f != null ) {
									if( log.isDebugEnabled() ) {
										log.debug( "Observation fact: " + variableName + " with value: TRUE using folder " + f.getName() ) ;
									}
									buildFact_NonGeneratedEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], f.getCode() ) ;
								}
								else {
									log.debug( "1: No ontological data found for: " + variableName + " with type: " + valueType ) ;
								}								
							}
						}						
					}
					//
					// We search for a variable with a given value (integer, date etc) where we
					// have targetted producing a generated enumeration from the value...
					else {
						Variable v = getOntologicalVariable( dataDocs[i].getValueSet().getValueTable(), variableName ) ;
						if( v != null ) {
							switch ( v.getType().intValue()) { 
							case Type.INT_AGE:
							case Type.INT_BEERNUMBER:
							case Type.INT_BICEPS:
							case Type.INT_CIGARETTENUMBER:
							case Type.INT_CIGARNUMBER:
							case Type.INT_DIASTOLICBP:
							case Type.INT_ETHNICITY:
							case Type.INT_HEARTRATE:
							case Type.INT_HEIGHT:
							case Type.INT_HIPS:
							case Type.INT_PIPENUMBER:							
							case Type.INT_RELATIVESNUMBER:
							case Type.INT_SMALLNUMBER:
							case Type.INT_SUBSCAPULAR:
							case Type.INT_SUPRAILIAC:
							case Type.INT_SYSTOLICBP:
							case Type.INT_TRICEPS:
							case Type.INT_WAIST:
							case Type.INT_WEIGHT:
							case Type.INT_WINESPIRITNUMBER:
							case Type.INT_YEAR:	
								//
								// Vital status added as a result of issue trac 94
							case Type.INT_VITALSTATUS:
								//
								// GENERATED_ENUMERATION will hopefully eventually replace all of the above,
								// which are deprecated...
							case Type.INT_GENERATED_ENUMERATION:
								//
								// The incidence of recent time enumerations is untidy, especially as this program
								// can be executed against a BRICCS/Onyx export file as well as a Brisskit/Onyx export file.
								// The former for the purposes of generating test data, or maybe at some time in the future
								// for BRICCS hosted on Brisskit infrastructure.
								// Either way, for Brisskit, the data type is INT_GENERATED_ENUMERATION
								// whilst for BRICCS the data type is INT_RECENTTIME.
								// This is the reason for the method buildFact_RecentTimeEnumeration() being invoked
								// from two potential situations here, one under Type.INT_GENERATED_ENUMERATION
								// and one under type Type.INT_RECENTTIME.
								// There is an added descriminator method isRecentTimeEnumeration() to help out
								// by dynamic inspection of the associated variables...
								if( isRecentTimeEnumeration( vvta[j], v ) ) {
									buildFact_RecentTimeEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], v ) ;	
								}
								else {
									log.debug( "Observation fact using generated enumeration: " + variableName + " with type: " + v.getType().toString() ) ;
									buildFact_GeneratedEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], v ) ;
								}							
								break;
								
							//
							// 	RECENTTIME covers a number of situations.
							//  The ones dealt with here are of the form day, hour and min;
							//  eg: "When did you last consume food?" Answer: TODAY 7 30 (in three pieces)
							case Type.INT_RECENTTIME:
								if( isRecentTimeEnumeration( vvta[j], v ) ) {
									buildFact_RecentTimeEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], v ) ;	
								}
								break ;
								
							case Type.INT_DATETIME:
								if( isNonGeneratedEnumeration( variableName ) ) {
									log.debug( variableName + " of type INT_DATETIME: " + getText( vvta[j].getValue() ) ) ;
									buildFact_NonGeneratedEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], v.getCode() ) ;
								}
								else {
									log.debug( "What to do with these? " + variableName + " with type: " + valueType ) ;
								}
								break ;
							case Type.INT_DECIMAL:
								buildFact_Numeric( obset, dataDocs[i].getValueSet(), vvta[j], v ) ;
								break ;
							case Type.INT_INTEGER:
								buildFact_Numeric( obset, dataDocs[i].getValueSet(), vvta[j], v ) ;
								break ;
							case Type.INT_TEXT:
								buildFact_Text( obset, dataDocs[i].getValueSet(), vvta[j], v ) ;
								break ;
							default:
								log.debug( "Ignoring: " + variableName + " with type: " + valueType ) ;
								break;
							}
						}	
						//========================================================================
						// This is a difficult section of code.
						// The variables are really Onyx variables rather than Briccs specific.
						// But I'm not sure whether different questionnaires might impose
						// extra participant variables. 
						// Needs some serious thought.
						//========================================================================
						else if( variableName.equals( "Admin.Participant.age" ) 
								 && 
								 valueType.equalsIgnoreCase( "INTEGER" ) ) {
							buildFact_GeneratedEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], "age", null ) ;
						}
						else if( variableName.equals( "Admin.Participant.pat_ethnicity" ) 
								 && 
								 valueType.equalsIgnoreCase( "TEXT" ) ) {
							buildFact_GeneratedEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], "pat_ethnicity", null ) ;
						}
						else if(  ( variableName.equals( "Admin.Participant.gender" ) || variableName.equals( "Admin.Participant.recruitmentType" ) )
								  && 
								  valueType.equalsIgnoreCase( "TEXT" ) ) {
							//
							// Gender and recruitmentType are categories where the value must be folded in to find
							// the ontology data. 
							// Also note that the top level is "Participants".
							String adjustedName = variableName + "." + getText( vvta[j].getValue() ) ;
							v = getOntologicalVariable( "Participants", adjustedName ) ;
							if( v != null ) {
								buildFact_NonGeneratedEnumeration( obset, dataDocs[i].getValueSet(), vvta[j], v.getCode() ) ;
							}
							else {
								log.error( "Could not find ontological data for: " + adjustedName )  ;
							}
						}
						else {
							log.debug( "2: No ontological data found for: " + variableName + " with type: " + valueType ) ;
						}						
					}
				}
			}
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildObservations()" ) ;
		}
	}
	
	private boolean isPatientDimensionRelated( String variableName ) {
		IncludeType[] includes = configDoc.getOnyxExportConfig().getPdoPhase().getPatientDimension().getIncludeArray() ;
		//
		// There may be multiple questionnaire parts (stages or entities) that contribute.
		// The outer loop deals with each of these from the configuration file...
		for( int i=0; i<includes.length; i++ ) {
			//
			// Each entity or stage may contribute more than one value / column.
			// The inner loop deals with each of these from the configuration file...
			PatientDimensionColumnType[] pdcta = includes[i].getPatientDimensionColumnArray() ;
			for( int j=0; j<pdcta.length; j++ ) {
				if( variableName.equals( pdcta[j].getVariable() ) ) {
					return true ;
				}
			}
		}
		return false ;
	}
	
//	/**
//	 * @param variableName
//	 * @return true if the variable concerns principle symptoms, false otherwise
//	 */
//	private boolean isPrincipleSymptoms( String variableName ) {
//		if( log.isTraceEnabled() ) enterTrace( "isPrincipleSymptoms()" ) ;
//		try {
//			String[] parts = variableName.split( "\\." ); 
//			if( parts[0].equals( "epi_symptoms" ) ) {
//				log.debug( "isPrincipleSymptoms: returning true!" ) ;
//				return true ;				
//			}
//			return false ;
//		}
//		finally {
//			if( log.isTraceEnabled() ) exitTrace( "isPrincipleSymptoms()" ) ;
//		}		
//	}
//	
//	/**
//	 * @param variableName
//	 * @return true if the variable concerns interventions this clinical episode,
//	 *         false otherwise.
//	 */
//	private boolean isInterventionThisClinicalEpisode( String variableName ) {
//		if( log.isTraceEnabled() ) enterTrace( "isInterventionThisClinicalEpisode()" ) ;
//		try {
//			if( variableName.startsWith( "epi_" ) ) {
//				for( int i=0; i<INTERVENTIONS_THIS_CLINICAL_EPISODE.length; i++ ) {
//					if( variableName.equals( INTERVENTIONS_THIS_CLINICAL_EPISODE[i] ) ) {
//						return true ;
//					}
//				}
//			}
//			return false ;
//		}
//		finally {
//			if( log.isTraceEnabled() ) exitTrace( "isInterventionThisClinicalEpisode()" ) ;
//		}
//	}
	
	/**
	 * The onset date/time of principle symptoms is non-trivial.
	 * <p/>
	 * I'm not even sure that there are not errors within the questionnaire.
	 * Using the web interface, there appears to be a choice along the lines
	 * of Year / Month with an additional possibility of Unknown. However, the 
	 * questionnaire builder leads me to believe there are additional choices
	 * relating to day and time with Unknown also available there. The latter is backed
	 * up by the metadata in the Onyx export file.
	 * <p/>
	 * This needs to be verified with Nick.
	 * <p/>
	 * <b>NB: Check the setting for Months, which is a zero based array!!!</b>
	 * 
	 * 
	 * @return The onset datetime of principle symptoms. Could be null.
	 */
//	private  Calendar constructPrincipleSymptomsDate() {
//		if( log.isTraceEnabled() ) enterTrace( "constructPrincipleSymptomsDate()" ) ;
//		Calendar psd = null ;
//		try {
//			//
//			// This is a painfully inefficient way of doing it.
//			// Optimization can wait until later!
//			String sEpiSymponset, sEpiSymponsetHour, sEpiSymponsetMin ;
//			String sEpiSymponsetYear, sEpiSymponsetMonth ;
//
//			sEpiSymponset = getValue( "DataSubmissionQuestionnaire", "epi_symponset" ) ;
//			if( sEpiSymponset != null ) {
//				sEpiSymponset = getCorrectedDateTimeValueFromString( sEpiSymponset ) ;
//				sEpiSymponsetHour = getValue( "DataSubmissionQuestionnaire", "epi_symponset_hour" ) ;
//				sEpiSymponsetMin = getValue( "DataSubmissionQuestionnaire", "epi_symponset_min" ) ;
//
//				XmlDateTime dt = XmlDateTime.Factory.newInstance() ;
//				dt.setStringValue( sEpiSymponset ) ;
//				psd = dt.getCalendarValue() ;
//				if( sEpiSymponsetHour != null && sEpiSymponsetMin != null) {
//					psd.set( Calendar.HOUR_OF_DAY, Integer.valueOf( sEpiSymponsetHour ) ) ;
//					psd.set( Calendar.MINUTE, Integer.valueOf( sEpiSymponsetMin ) ) ;
//				}
//				log.debug( "Principle symptoms date. Route1. " + psd.toString() ) ;
//				return psd ;
//			}
//			sEpiSymponsetYear = getValue( "DataSubmissionQuestionnaire", "epi_symponset_year" ) ;
//			if( sEpiSymponsetYear != null ) {
//				sEpiSymponsetMonth = getValue( "DataSubmissionQuestionnaire", "epi_symponset_month" ) ;
//				psd = Calendar.getInstance() ;
//				psd.setTimeInMillis( 0 ) ;
//				psd.set( Integer.valueOf( sEpiSymponsetYear ), Integer.valueOf( sEpiSymponsetMonth ), 1, 0, 0) ;
//				log.debug( "Principle symptoms date. Route2. " + psd.toString() ) ;
//				return psd ;
//			}
//		}
//		finally {
//			if( log.isTraceEnabled() ) exitTrace( "constructPrincipleSymptomsDate()" ) ;
//		}
//		log.debug( "Principle symptoms date. Route3. " + psd ) ;
//		return psd ;	
//	}
	
	/**
	 * Builds an enumeration / category already designed into the questionnaire.
	 * (With the exception of type recent time).
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @param variable
	 */
	private void buildFact_NonGeneratedEnumeration( ObservationSet obset
			                                      , ValueSetType valueSet
			                                      , VariableValueType value
			                                      , String code ) {
		if( log.isTraceEnabled() ) enterTrace( "buildFact_NonGeneratedEnumeration()" ) ;
		try {
			if( log.isDebugEnabled() ) {
				log.debug( "Onyx variable: " + value.getVariable() ) ;
			}
			ObservationType observation = buildFact_StandardColumns( obset, valueSet, value ) ;
			buildFact_ValueColumnsForEnumeration( observation ) ;
			ConceptCd concept_cd = observation.addNewConceptCd() ;
			concept_cd.setName( "some name" ) ;
			concept_cd.setStringValue( code ) ;
 		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildFact_NonGeneratedEnumeration()" ) ;
		}
	}
	
	private void buildFact_ValueColumnsForEnumeration( ObservationType observation ) {
		//
		// Set modifier to missing value...
		ModifierCd modifier_cd = observation.addNewModifierCd() ;
		modifier_cd.setName( "missing value" ) ;
		modifier_cd.setStringValue( "@" ) ;
		//
		// Set units code to missing value...
		observation.setUnitsCd( "@" ) ;
		//
		// Set value type code to text...		
		observation.setValuetypeCd( "T") ;
	}
	
	/**
	 * Builds an integer observation
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @param variable
	 */
	private void buildFact_Numeric( ObservationSet obset
			                      , ValueSetType valueSet
			                      , VariableValueType value
			                      , Variable variable ) {
		if( log.isTraceEnabled() ) enterTrace( "buildFact_Numeric()" ) ;
		try {
//			if( this.bNominalOntology ) {
//				return ;
//			}
			if( log.isDebugEnabled() ) {
				log.debug( "Numeric fact: " + value.getVariable() ) ;
			}
			//
			// Standard columns...
			ObservationType observation = buildFact_StandardColumns( obset, valueSet, value ) ;
			//
			// Set modifier to missing value...
			ModifierCd modifier_cd = observation.addNewModifierCd() ;
			modifier_cd.setName( "missing value" ) ;
			modifier_cd.setStringValue( "@" ) ;
			//
			// Set units code to missing value...
			observation.setUnitsCd( "@" ) ;
			//
			// Numeric value columns...
			observation.setValuetypeCd( "N" ) ;  // for numeric
			observation.setTvalChar( "E" ) ;     // for equals
			BigDecimal measure = new BigDecimal( getText( value.getValue() ).trim() ) ;
			//
			// If this is a test run, we adjust the value by a small amount
			if( OnyxData2Pdo.testFlag == true ) {
  				BigDecimal adjustment = new BigDecimal( this.testNumericsAdjustment ) ; 
  				if( measure.intValue() >= 0 ) {
  					measure = measure.add( adjustment ) ;
  				}
  				else {
  					measure = measure.subtract( adjustment ) ;
  				}  				
			}			
			NvalNum nvn = ObservationType.NvalNum.Factory.newInstance() ;		
			nvn.setBigDecimalValue( measure ) ;
			nvn.setUnits( "@" ) ;
			observation.setNvalNum( nvn ) ;
			//
			// Finally the concept code...
			ConceptCd concept_cd = observation.addNewConceptCd() ;
			concept_cd.setName( "some name" ) ;
			concept_cd.setStringValue( variable.getCode() ) ;
 		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildFact_Numeric()" ) ;
		}
	}
	
	private void buildFact_Text( ObservationSet obset
			                   , ValueSetType valueSet
			                   , VariableValueType value
			                   , Variable variable ) {
		if( log.isTraceEnabled() ) enterTrace( "buildFact_Text()" ) ;
		try {
			if( this.bNominalOntology ) {
				return ;
			}
			if( log.isDebugEnabled() ) {
				log.debug( "Text fact: " + value.getVariable() ) ;
			}
			//
			// Standard columns...
			ObservationType observation = buildFact_StandardColumns( obset, valueSet, value ) ;
			//
			// Set modifier to missing value...
			ModifierCd modifier_cd = observation.addNewModifierCd() ;
			modifier_cd.setName( "missing value" ) ;
			modifier_cd.setStringValue( "@" ) ;
			//
			// Set units code to missing value...
			observation.setUnitsCd( "@" ) ;
			//
			// Numeric value columns...
			observation.setValuetypeCd( "T" ) ;  
			observation.setTvalChar( getText( value.getValue() ) ) ;     
			//
			// Finally the concept code...
			ConceptCd concept_cd = observation.addNewConceptCd() ;
			concept_cd.setName( "some name" ) ;
			concept_cd.setStringValue( variable.getCode() ) ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildFact_Text()" ) ;
		}
	}
	
	/**
	 * Convenience method for building a fact based upon a generated enumeration.
	 * Delegates to method with extra parameter of parent folder name.
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @param varMetadata
	 * @throws ProcessException
	 */
	private void buildFact_GeneratedEnumeration( ObservationSet obset
			                                   , ValueSetType valueSet
			                                   , VariableValueType value
			                                   , Variable varMetadata ) throws ProcessException {
		buildFact_GeneratedEnumeration( obset, valueSet, value, getParentOfVariable( varMetadata ).getName(), varMetadata ) ;
	}
	
	/**
	 * Builds a fact based upon a generated enumeration.
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @param folderName
	 * @param varMetadata
	 * @throws ProcessException
	 */
	private void buildFact_GeneratedEnumeration( ObservationSet obset
			                                   , ValueSetType valueSet
			                                   , VariableValueType value
			                                   , String folderName
			                                   , Variable varMetadata ) throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "buildFact_GeneratedEnumeration()" ) ;
		try {
			ObservationType observation = buildFact_StandardColumns( obset, valueSet, value ) ;
			buildFact_ValueColumnsForEnumeration( observation ) ;
			ConceptCd concept_cd = observation.addNewConceptCd() ;
			concept_cd.setName( "some name" ) ;
			concept_cd.setStringValue( buildEnumeratedCode( obset, valueSet, value, folderName, varMetadata ) ) ;		
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildFact_GeneratedEnumeration()" ) ;
		}
	}
	
	/**
	 * Vital status indicates whether a patient is deceased, living, or unknown.
	 * It is not present in Onyx data, either metadata or participant data.
	 * It is one of the standard analysis types within i2b2, which makes it desirable.
	 * This routine adds it as an observation fact with a hard-coded value of "Living".
	 * (There is also a value added to the patient_dimension, but this is achieved
	 * within routine buildPatientDimension().
	 * 
	 * @param obset
	 * @throws ProcessException
	 */
//	private void buildVitalStatusFact( ObservationSet obset ) throws ProcessException {
//		if( log.isTraceEnabled() ) enterTrace( "buildVitalStatusFact()" ) ;
//		try {
//			
//			ObservationType observation = obset.addNewObservation() ;
//			//
//			// Set event id (also known as encounter number ) ...
//			EventId event_id = observation.addNewEventId() ;
//			event_id.setSource( getCurrentEncounterSource() ) ;
//			event_id.setStringValue( getCurrentEncounterNum() ) ;
//			//
//			// Set observer_cd (also known as provider) ...
//			ObserverCd observer_cd = observation.addNewObserverCd() ;
//// Spelling mistake corrected in i2b2 version 1.6,
//// but only by eliminating the observer source!!!
////			observer_cd.setSoruce( getCurrentObserverSource() ) ;
//			observer_cd.setStringValue( getCurrentObserverNum() ) ;
//			//
//			// Set patient number...
//			PatientIdType patient_id = observation.addNewPatientId() ;
//			patient_id.setSource( getCurrentPatientSource() ) ;
//			patient_id.setStringValue( getCurrentPatientNum() ) ;
//			//
//			// Set the start date of the observation...
//			XmlObject xo = getValueAsXmlObject( "Participants", "Admin.Participant.captureStartDate" ) ;
//			Calendar cal = getCorrectedDateTimeValue( xo ) ;
//			if( OnyxData2Pdo.testFlag == true ) {
//				cal = adjustCalendarForTestRun( cal ) ;
//			}
//			observation.setStartDate( cal ) ;
//			//
//			// Set modifier to missing value...
//			ModifierCd modifier_cd = observation.addNewModifierCd() ;
//			modifier_cd.setName( "missing value" ) ;
//			modifier_cd.setStringValue( "@" ) ;
//			//
//			// Set units code to missing value...
//			observation.setUnitsCd( "@" ) ;
//			//
//			// Set the end date of the observation...
//			xo = getValueAsXmlObject( "Participants", "Admin.Participant.captureEndDate" ) ;
//			cal = getCorrectedDateTimeValue( xo ) ;
//			if( OnyxData2Pdo.testFlag == true ) {
//				cal = adjustCalendarForTestRun( cal ) ;
//			}
//			observation.setEndDate( cal ) ;
//			//
//			// Set value type code to text...		
//			observation.setValuetypeCd( "T") ;
//			//
//			// Set location code to missing value...
//			LocationCd location_cd = observation.addNewLocationCd() ;
//			location_cd.setName( "missing value" ) ;
//			location_cd.setStringValue( "@" ) ;
//			//
//			// Set admin group...
//			observation.setUpdateDate( adminCalendar ) ;
//			observation.setDownloadDate( adminCalendar ) ;
//			observation.setImportDate( adminCalendar ) ;
//			observation.setSourcesystemCd( this.projectAcronymn ) ;
//			
//						
//			ConceptCd concept_cd = observation.addNewConceptCd() ;
//			concept_cd.setName( "some name" ) ;
//						
//			EnumeratedVariableDocument evd = getEnumeratedVariableDocument( "vital_status" ) ;
//
//			String code = null ;
//			//
//			// Declare the namespace that will be used...
//			String revNamespace = "declare namespace rev='http://brisskit.org/xml/onyxmetadata-rev/v1.0/rev';" ;
//			XmlCursor cursor = null ;
//
//			cursor = evd.newCursor() ;
//			cursor.selectPath( revNamespace + "$this//rev:variable" ) ;
//			while( cursor.toNextSelection() ) {
//				String name = ((RevVariable)cursor.getObject()).getName() ;
//				// BEWARE! This is weak.
//				// It gets over the padding of spaces to the left in a variable name.
//				if( name.endsWith( "Living" ) ) {
//					code = ((RevVariable)cursor.getObject()).getCode() ;
//				}
//			}
//			cursor.dispose() ;
//			concept_cd.setStringValue( code ) ;
//		}
//		finally {
//			if( log.isTraceEnabled() ) exitTrace( "buildVitalStatusFact()" ) ;
//		}
//	}
	
	private boolean isRecentTimeEnumeration(  VariableValueType value, Variable variable ) {
		boolean retValue = false ;
		String ucsv = getText( value ).toUpperCase() ;
		if( variable.getName().contains( "day" ) 
			&&
			( ucsv.contains( "TODAY" ) || ucsv.contains( "YESTERDAY" ) || ucsv.contains( "MORE THAN 24 HOURS" ) ) ) {
			retValue = true ;
		}
		return retValue ;
	}
	
	/**
	 * Builds an enumeration based upon type RECENTTIME.<p/>
	 * 
	 * <b>NOTES:</b><br/>
	 * (i)  Three variables are required for this, only one of which is passed in as a VariableValueType.
	 * The whole process is driven by a "day" parameter. The hours and minutes are picked up dynamically.
	 * ( The process is invoked <em><b>whenever</b></em> a RECENTTIME type is encountered, but simply ignores
	 * "hour" and "minute" invocations, as we know these will be accounted for dynamically on the "day" 
	 * invocation ).<br/> 
	 * (ii) Delegates the code build to a specialized routine.
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @param variable
	 * @throws ProcessException
	 */
	private void buildFact_RecentTimeEnumeration( ObservationSet obset
			                                    , ValueSetType valueSet
			                                    , VariableValueType value
			                                    , Variable variable ) 
	    throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "buildFact_RecentTimeEnumeration()" ) ;
		try {
//			String ucsv = getText( value ).toUpperCase() ;
//			if( variable.getName().contains( "day" ) 
//				&&
//				( ucsv.contains( "TODAY" ) || ucsv.contains( "YESTERDAY" ) || ucsv.contains( "MORE THAN 24 HOURS" ) ) ) {
				ObservationType observation = buildFact_StandardColumns( obset, valueSet, value ) ;
				buildFact_ValueColumnsForEnumeration( observation ) ;
				String code = buildRecentTimeEnumeratedCode( obset, valueSet, value, variable ) ;				
				ConceptCd concept_cd = observation.addNewConceptCd() ;
				concept_cd.setName( "some name" ) ;
				concept_cd.setStringValue( code ) ;
//			}		
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildFact_RecentTimeEnumeration()" ) ;
		}
	}
	
	/**
	 * Builds the "standard" columns for an observation fact.
	 * <p/>
	 * <b>This is a critical routine and requires serious examination!</b>
	 * <p/>
	 * <b>NOTES:</b><br/>
	 * (1) Includes a <b><em>newly generated</em></b> event id (or encounter number). 
	 *     Also BRICCS as a source id. Questions: How do we really know this is the next
	 *     event id? How would we deal with this in a web service version where we did not
	 *     know (or were not in control of) generating this number? <br/>
	 * (2) Includes a <b><em>newly generated</em></b> observer code (or provider). 
	 *     This includes BRICCS as a source id. The observer code is set to the missing
	 *     value flag. Is this acceptable?<br/>
	 * (3) Includes the <b><em>current</em></b> patient id with source id set to "HIVE". 
	 *     Questions: How do we really know the current patient id? How would we deal with 
	 *     this in a web service version where we did not know (or were not in control of) 
	 *     this number? If the source were not "HIVE", but say participant id, would the mapping
	 *     in the mapping table be used to return the correct internal patient number?
	 *     This is critical if we are talking of using PDO for updating information!<br/>
	 * (4) Includes start date and end date for the observation. End date is nullable.
	 *     Delegates the formation of these dates to specialized routines. <br/>
	 * (5) Modifier code is set to missing value flag. <br/>
	 * (6) Units code is set to missing value flag. <br/>
	 * (7) Value type code is set to text ("T"); ie: all facts are text. <br/>
	 * (8) Location code is set to missing value flag. <br/>
	 * (9) The admin group of fields is set here. Update date, download date and import date
	 *     are all set to a single value (date at runtime or fixed nonesense test date). 
	 *     The source system id is set to BRICCS. The upload id is set to 1.
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @return
	 */
	private ObservationType buildFact_StandardColumns( ObservationSet obset, ValueSetType valueSet, VariableValueType value ) {
		if( log.isTraceEnabled() ) enterTrace( "buildFact_StandardColumns()" ) ;
		log.debug( "value.getVariable(): " + value.getVariable() ) ;
		ObservationType observation = obset.addNewObservation() ;
		//
		// Set event id (also known as encounter number ) ...
		EventId event_id = observation.addNewEventId() ;
		event_id.setSource( getCurrentEncounterSource() ) ;
		event_id.setStringValue( String.valueOf( getCurrentEncounterNum() ) ) ;
		//
		// Set observer_cd (also known as provider) ...
		ObserverCd observer_cd = observation.addNewObserverCd() ;
// Spelling mistake corrected in i2b2 version 1.6,
// but only by eliminating the observer source!!!
//		observer_cd.setSoruce( getCurrentObserverSource() ) ;
		observer_cd.setStringValue( getCurrentObserverNum() ) ;
		//
		// Set patient number...
		PatientIdType patient_id = observation.addNewPatientId() ;
		patient_id.setSource( getCurrentPatientSource() ) ;
		patient_id.setStringValue( getCurrentPatientNum() ) ;
		//
		// Set the start date of the observation...
		observation.setStartDate( getStartDate( valueSet, value ) ) ;
		//
		// Set the end date of the observation...
		observation.setEndDate( getEndDate( valueSet, value ) ) ;
		//
		// Set location code to missing value...
		LocationCd location_cd = observation.addNewLocationCd() ;
		location_cd.setName( "missing value" ) ;
		location_cd.setStringValue( "@" ) ;
		//
		// Set admin group...
		observation.setUpdateDate( adminCalendar ) ;
		observation.setDownloadDate( adminCalendar ) ;
		observation.setImportDate( adminCalendar ) ;
		observation.setSourcesystemCd( this.projectAcronymn ) ;
		if( log.isTraceEnabled() ) exitTrace( "buildFact_StandardColumns()" ) ;
		return observation ;
	}
	
	private ObservationType _buildFact_StandardColumns( ObservationSet obset, ValueSetType valueSet, VariableValueType value ) {
		if( log.isTraceEnabled() ) enterTrace( "buildFact_StandardColumns()" ) ;
		log.debug( "value.getVariable(): " + value.getVariable() ) ;
		ObservationType observation = obset.addNewObservation() ;
		//
		// Set event id (also known as encounter number ) ...
		EventId event_id = observation.addNewEventId() ;
		event_id.setSource( getCurrentEncounterSource() ) ;
		event_id.setStringValue( String.valueOf( getCurrentEncounterNum() ) ) ;
		//
		// Set observer_cd (also known as provider) ...
		ObserverCd observer_cd = observation.addNewObserverCd() ;
// Spelling mistake corrected in i2b2 version 1.6,
// but only by eliminating the observer source!!!
//		observer_cd.setSoruce( getCurrentObserverSource() ) ;
		observer_cd.setStringValue( getCurrentObserverNum() ) ;
		//
		// Set patient number...
		PatientIdType patient_id = observation.addNewPatientId() ;
		patient_id.setSource( getCurrentPatientSource() ) ;
		patient_id.setStringValue( getCurrentPatientNum() ) ;
		//
		// Set the start date of the observation...
		observation.setStartDate( getStartDate( valueSet, value ) ) ;
		//
		// Set modifier to missing value...
		ModifierCd modifier_cd = observation.addNewModifierCd() ;
		modifier_cd.setName( "missing value" ) ;
		modifier_cd.setStringValue( "@" ) ;
		//
		// Set units code to missing value...
		observation.setUnitsCd( "@" ) ;
		//
		// Set the end date of the observation...
		observation.setEndDate( getEndDate( valueSet, value ) ) ;
		//
		// Set value type code to text...		
		observation.setValuetypeCd( "T") ;
		//
		// Set location code to missing value...
		LocationCd location_cd = observation.addNewLocationCd() ;
		location_cd.setName( "missing value" ) ;
		location_cd.setStringValue( "@" ) ;
		//
		// Set admin group...
		observation.setUpdateDate( adminCalendar ) ;
		observation.setDownloadDate( adminCalendar ) ;
		observation.setImportDate( adminCalendar ) ;
		observation.setSourcesystemCd( this.projectAcronymn ) ;
		if( log.isTraceEnabled() ) exitTrace( "buildFact_StandardColumns()" ) ;
		return observation ;
	}
	
	/**
	 * Builds an ontology code for an enumeration involving a RECENTTIME type. 
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @param variable
	 * @return
	 * @throws ProcessException
	 */
	private String buildRecentTimeEnumeratedCode( ObservationSet obset
			                                    , ValueSetType valueSet
			                                    , VariableValueType value
			                                    , Variable variable ) throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "buildRecentTimeEnumeratedCode()" ) ;
		EnumeratedVariableDocument evd = getEnumeratedVariableDocument( variable ) ;
		//
		// Declare the namespace that will be used...
		String revNamespace = "declare namespace rev='http://brisskit.org/xml/onyxmetadata-rev/v1.0/rev';" ;
		XmlCursor cursor = evd.newCursor() ;
		//
		// Here is the driving "day" value as a string.
		String day = getText( value.getValue() ).toUpperCase() ;
		
		//
		// Format to make sure hours always take up 2 character spaces...
		String hourFormat = "%2d" ;
		
		try {
			if( day.contains( "TODAY" ) ) {
				String[] bits = getHoursAndMinutes( value, variable ) ;
				String xpath = revNamespace 
				        + "$this//rev:group[@name='Hour " 
				        + String.format( hourFormat, Integer.parseInt( bits[0] ) )
				        + "']/rev:variable[@name='Min " + bits[1] + "']" ;
				cursor.selectPath( xpath ) ;				
			}
			else if( day.contains( "YESTERDAY") ) {
				String[] bits = getHoursAndMinutes( value, variable ) ;
				cursor.selectPath( revNamespace 
						+ "$this//rev:group[@name='Hour " 
						+ String.format( hourFormat, Integer.parseInt( bits[0] ) ) 
						+ "']/rev:variable[@name='Min " + bits[1] + "']" ) ;
			}
			else if( day.contains( "24" ) ) {
				cursor.selectPath( revNamespace + "$this//rev:variable[@name='More than 24 hours']" ) ;
			}
			cursor.toNextSelection() ;
			return ((RevVariable)cursor.getObject()).getCode() ;							
		}
		finally {
			if( cursor != null ) { cursor.dispose() ; }	
			if( log.isTraceEnabled() ) exitTrace( "buildRecentTimeEnumeratedCode()" ) ;
		}
	}
	
	/**
	 * Utility routine for generating hours and minutes for a RECENTTIME.
	 * 
	 * @param dayValue
	 * @param variable
	 * @return
	 */
	private String[] getHoursAndMinutes( VariableValueType dayValue, Variable variable ) {
		String[] hourAndMinutes = new String[2] ;
		String hourName = null ;
		String minName = null ;
		XmlCursor cursor = variable.newCursor() ;
		try {			
			cursor.toParent() ;
			Folder folder = (Folder)cursor.getObject() ;
			Variable[] va = folder.getVariableArray() ;			
			for( int i=0; i<va.length; i++ ) {
				if( va[i].getName().toUpperCase().contains( "HOUR" ) ) {
					hourName = va[i].getName() ;
				}
				else if( va[i].getName().toUpperCase().contains( "MIN" ) ) {
					minName = va[i].getName() ;
				}
			}
			cursor.dispose() ;
			cursor = dayValue.newCursor() ;
			cursor.toParent() ;
			cursor.toFirstChild() ;
			do {
				if( cursor.getObject() instanceof VariableValueType ) {
					VariableValueType vvt = (VariableValueType)cursor.getObject() ;
					if( vvt.getVariable().contains( hourName ) ) {
						hourAndMinutes[0] = getText( vvt) ;
					}
					else if( vvt.getVariable().contains( minName ) ) {
						hourAndMinutes[1] = getText( vvt) ;
					}
				}
			} while ( cursor.toNextSibling() || ( hourAndMinutes[0] == null && hourAndMinutes[1] == null ) ); 
			log.debug( "hourAndMinutes: " + hourAndMinutes[0] + ":" + hourAndMinutes[1] ) ;
			return hourAndMinutes ;
		}
		finally {
			cursor.dispose() ;
		}
		
	}
	
	/**
	 * Builds an ontology code for an enumeration (excluding RECENTTIME). 
	 * 
	 * @param obset
	 * @param valueSet
	 * @param value
	 * @param folderName
	 * @param varMetadata
	 * @return
	 * @throws ProcessException
	 */
	private String buildEnumeratedCode( ObservationSet obset
			                          , ValueSetType valueSet
			                          , VariableValueType value
			                          , String folderName 
			                          , Variable varMetadata ) throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "buildEnumeratedCode()" ) ;
		if( value.getVariable().equals( "Admin.Participant.age" ) ) {
			log.debug( "Admin.Participant.age" ) ;
		}
		
		EnumeratedVariableDocument evd = getEnumeratedVariableDocument( folderName ) ;
		//
		// Here is the value as a string.
		// We need to match it against some variable name, which maybe padded with spaces to the left!!!!
		String sv = getText( value.getValue() ) ;
		//
		// If it has a decimal point, we omit the right hand side...
		sv = sv.split( "\\." )[0] ;	
		//
		// This needs something of a rethink!
		// Although it will pass muster for test data extraction from Briccs.
		//
		// We check for any types which need adjusting for test data
		// (We can only do this if metadata is available of course)...
		if( OnyxData2Pdo.testFlag == true ) {
			if( varMetadata != null ) {
				//
				// Only YEAR and AGE are in this category at present...
				// Ah! The reason these are not working under test conditions is that
				// Type.YEAR and Type.AGE are deprecated in favour of Type.GENERATED_ENUMERATION
				// so this does need another think.
				if( varMetadata.getType() == Type.YEAR ) {
					try {
						// NB: This appears never to be executed...
						sv = String.valueOf( Integer.valueOf( sv ) - this.testYearAdjustment ) ;
					}
					catch( NumberFormatException nfx ) {
						log.error( "Could not adjust YEAR for test purposes. \n" +
								   "Folder name: [" + folderName + "] " +
								   "Variable name: [" + varMetadata.getName() + "]") ;
					}
				}
				if( varMetadata.getType() == Type.AGE ) {
					try {
						// NB: This appears never to be executed...
						sv = String.valueOf( Integer.valueOf( sv ) + this.testYearAdjustment ) ;
					}
					catch( NumberFormatException nfx ) {
						log.error( "Could not adjust AGE for test purposes. \n" +
								   "Folder name: [" + folderName + "] " +
								   "Variable name: [" + varMetadata.getName() + "]") ;
					}
				}
			}
			// NB: Only with this did I get the participant age as observation fact altered in test.
			//     Does looke as if something else is required.
			//     We cannot assume the folder is called "age"...
			else if( folderName.equals( "age" ) ) {
				try {
					sv = String.valueOf( Integer.valueOf( sv ) + this.testYearAdjustment ) ;
				}
				catch( NumberFormatException nfx ) {
					log.error( "Could not adjust YEAR for test purposes. \n" +
							   "Folder name: [" + folderName + "] " ) ;
				}
			}
		}
		
		//
		// Declare the namespace that will be used...
		String revNamespace = "declare namespace rev='http://brisskit.org/xml/onyxmetadata-rev/v1.0/rev';" ;
		XmlCursor cursor = null ;
		try {
			cursor = evd.newCursor() ;
			cursor.selectPath( revNamespace + "$this//rev:variable" ) ;
			while( cursor.toNextSelection() ) {
				String name = ((RevVariable)cursor.getObject()).getName() ;
				// BEWARE! This is weak.
				// It gets over the padding of spaces to the left in a variable name.
				if( name.endsWith( sv ) ) {
					return ((RevVariable)cursor.getObject()).getCode() ;
				}
			}
			cursor.dispose() ;
			//
			// We try one another strategy, supposing that this is a text enumeration with a sponge category (eg: OTHER)...
			cursor = evd.newCursor() ;
			cursor.selectPath( revNamespace + "$this//rev:variable" ) ;
			while( cursor.toNextSelection() ) {
				String name = ((RevVariable)cursor.getObject()).getName().trim() ;
				for( int i=0; i<SPONGE_CATEGORIES.length; i++ ) {
					if( name.equalsIgnoreCase( SPONGE_CATEGORIES[i] ) ) {
						return ((RevVariable)cursor.getObject()).getCode() ;
					}
				}				
			}			
			log.error( "Failed to build enumerated code for folder " + folderName + " for variable with value " + sv ) ;
			return null ;							
		}
		finally {
			if( cursor != null ) { cursor.dispose() ; }	
			if( log.isTraceEnabled() ) exitTrace( "buildEnumeratedCode()" ) ;
		}
	}
	
	/**
	 * Utility routine to acquire the "enumerated" ontology document for a given variable.
	 * <p/>
	 * Delegate the real work.
	 * 
	 * @param variable
	 * @return
	 * @throws ProcessException
	 */
	private EnumeratedVariableDocument getEnumeratedVariableDocument( Variable variable ) throws ProcessException {
		return getEnumeratedVariableDocument( getParentOfVariable( variable ).getName() ) ;
	}
	
	/**
	 * Routine to acquire the "enumerated" ontology document for a given folder.
	 * 
	 * @param folderName
	 * @return
	 * @throws ProcessException
	 */
	private EnumeratedVariableDocument getEnumeratedVariableDocument( String folderName ) throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "getEnumeratedVariableDocument(String)" ) ;
		StringBuilder b = new StringBuilder() ;
		b.append( enumDirectory.getAbsolutePath() )
		 .append( System.getProperty( "file.separator" ) ) 
		 .append( folderName ) 
		 .append( ".xml" ) ;
		String fullPath = b.toString() ;
		log.debug( "fullPath: " + fullPath ) ;	
		File file = new File(fullPath) ;
		try {
			EnumeratedVariableDocument evd = EnumeratedVariableDocument.Factory.parse( file ) ;
			log.debug( "Parsed file; " + fullPath ) ;
			return evd ;
		}
		catch( IOException iox ) {
			throw new ProcessException( "Something wrong with enumerated variable file:" + file.getPath(), iox ) ;
		}
		catch( XmlException xmlx ) {
			throw new ProcessException( "Could not parse with enumerated variable file:" + file.getPath(), xmlx ) ;   			
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getEnumeratedVariableDocument(String)" ) ;
		}	
	}
	
	/**
	 * Utility routine for getting the parent folder of a variable.
	 * 
	 * @param variable
	 * @return
	 */
	private Folder getParentOfVariable( Variable variable ) {
		XmlCursor cursor = variable.newCursor() ;
		try {
			cursor.toParent() ;
			return (Folder)cursor.getObject() ;
		}
		finally {
			cursor.dispose() ;
		}
	}
	
	/**
	 * Utility routine which acquires a variable from the refined ontology file given
	 * a value set name and a variable name.
	 * 
	 * If the supplied ontology is a real research ontology (as opposed to a nominal ontology
	 * extracted from the questionnaire), then we search the collection of ont codes extracted
	 * from the ontology.
	 * 
	 * Otherwise we search the nominal ontology using a path.
	 * 
	 * @param valueSetName
	 * @param name
	 * @return
	 */
	private Variable getOntologicalVariable( String valueSetName , String name ) {
		if( this.bNominalOntology == false ) {
			//
			// Use the variables collection if the ontology is real...
			return searchForVariableUsingCollection( valueSetName, name ) ;
		}
		else {
			//
			// Otherwise use the inherent path within the nominal ontology...
			return searchUsingPath( valueSetName, name ) ;
		}
	}
	
	/**
	 * Utility routine which acquires a folder from the refined ontology file given
	 * a value set name and an onyx variable name.
	 * 
	 * If the supplied ontology is a real research ontology (as opposed to a nominal ontology
	 * extracted from the questionnaire), then we search the collection of ont codes extracted
	 * from the ontology.
	 * 
	 * @param valueSetName
	 * @param name
	 * @return
	 */
	private Folder getOntologicalFolder( String valueSetName , String name ) {
		if( this.bNominalOntology == false ) {
			//
			// Use the codes collection if the ontology is real...
			return searchForFolderUsingCollection( valueSetName, name ) ;
		}
		return null ;
	}
	
	private Variable searchForVariableUsingCollection( String valueSetName , String variableName ) {
		Variable v = null ;
		//
		// Trigger for a standardized ontological code within the variable name 
		// is a double underscore...
		if( variableName.indexOf( "__" ) != -1 ) {
			String[] parts = variableName.split( "__" ) ;
			String unrefinedCode = parts[ parts.length-1 ] ;
			// Ontological codes are of the form AA:xxxxxxxx
			// We need to place the colon in the code string
			// and also replace any underscores with a full stop...
			// So, ICZ72_1 becomes IC:Z72.1
			String code = unrefinedCode.substring( 0, 2 ) + ':' + unrefinedCode.substring( 2 ).replace( '_', '.' ) ;
			XmlObject xo = this.ontologicalVariables.get( code ) ;
			if( xo instanceof Variable ) {
				v = (Variable)xo ;
			}
		}
		//
		// If no standardized code found, 
		// need to generate a hash for "local" codes...
		//
		// Notice the absence of an ELSE statement here!
		// We do not assume the above code will succeed.
		// ie: if we cannot find a standard code, we search for a hash code instead.
		// This is to cater for difficult variable names such as:
		// HOUSEHOLD_NUMBER.VALUE__SM224525003.PEOPLE
		if( v == null ) {
			String code = this.nominalCodePrefix + OnyxData2Pdo.generateHash( variableName ) ;
			XmlObject xo = this.ontologicalVariables.get( code ) ;
			if( xo instanceof Variable ) {
				v = (Variable)xo ;
			}
//			if( log.isDebugEnabled() ) {
//				if( variableName.contains( "SM224525003" )
//			        ||
//			        variableName.contains( "PEOPLE" ) ) {
//					String debugCode = this.nominalCodePrefix + OnyxData2Pdo.generateHash( "HOUSEHOLD_NUMBER.VALUE__SM224525003.PEOPLE" ) ;
//					log.debug( "HOUSEHOLD_NUMBER.VALUE__SM224525003.PEOPLE produced: " + debugCode ) ;
//					debugCode = this.nominalCodePrefix + OnyxData2Pdo.generateHash( "VALUE__SM224525003.PEOPLE" ) ;
//					log.debug( "VALUE__SM224525003.PEOPLE produced: " + debugCode ) ;
//					debugCode = this.nominalCodePrefix + OnyxData2Pdo.generateHash( "PEOPLE" ) ;
//					log.debug( "PEOPLE produced: " + debugCode ) ;
//				}
//			}
		}
		
		if( v == null ) {
			if( log.isDebugEnabled() ) {
				log.debug( "Could not form code for variable name: [" + variableName + 
						   "] within value set: [" + valueSetName + "]" ) ;
			}			
		}
		
		return v ;
	}
	
	private Folder searchForFolderUsingCollection( String valueSetName , String name ) {
		Folder f = null ;
		//
		// Trigger for a standardized ontological code within the folder name 
		// is a double underscore...
		if( name.indexOf( "__" ) != -1 ) {
			String[] parts = name.split( "__" ) ;
			String unrefinedCode = parts[ parts.length-1 ] ;
			// Ontological codes are of the form AA:xxxxxxxx
			// We need to place the colon in the code string...
			String code = unrefinedCode.substring( 0, 2 ) + ':' + unrefinedCode.substring( 2 ) ;
			XmlObject xo = this.ontologicalVariables.get( code ) ;
			if( xo instanceof Folder ) {
				f = (Folder)xo ;
			}
		}
		//
		// If no standardized code found, 
		// need to generate a hash for "local" codes...
		else {
			String code = this.nominalCodePrefix + OnyxData2Pdo.generateHash( name ) ;
			XmlObject xo = this.ontologicalVariables.get( code ) ;
			if( xo instanceof Folder ) {
				f = (Folder)xo ;
			}
		}
		
		if( f == null ) {
			log.warn( "Could not form code for folder name: [" + name + 
					   "] within value set: [" + valueSetName + "]" ) ;
		}		
		return f ;
	}
	
	/**
	 * Utility routine which acquires a variable from the refined ontology file given
	 * a value set name and a variable name.
	 * 
	 * @param valueSetName
	 * @param variableName
	 * @return
	 */
	private Variable searchUsingPath( String valueSetName , String variableName ) {
		//
		// Declare the namespace that will be used...
		String omrNamespace = "declare namespace omr='http://brisskit.org/xml/onyxmetadata-refined/v1.0/omr';" ;
		XmlCursor cursor = null ;
		try {
			//
			// Two strategies are applied...
			
			// (1) If the  name is dot-qualified, we split the name.
			//     Only the lowest part is a variable. The rest are folders.
			//     So we search for the lowest folder/variable within the highest folder with the valueSetName.
			String[] parts = variableName.split( "\\." ) ;
			if( parts.length > 1 ) {
				String lowestFolderName = parts[ parts.length-2 ] ;
				String baseVariableName = parts[ parts.length-1 ] ;
				Folder[] highestFolders = this.metadataDoc.getContainer().getFolderArray() ;
				if( highestFolders.length == 1) {
					highestFolders = highestFolders[0].getFolderArray() ;
				}
				for( int i=0; i<highestFolders.length; i++ ) {
					if( highestFolders[i].getName().equals( valueSetName ) ) {
						cursor = highestFolders[i].newCursor() ;
						cursor.selectPath( omrNamespace + "$this//omr:folder[@name='" + lowestFolderName + "']/omr:variable[@name='" + baseVariableName + "']" ) ;
						if( cursor.toNextSelection() ) {
							return (Variable)cursor.getObject() ;
						}
						else {
							return null ;
						}
						
					}
				}
				return null ;
			}
			else {
			// (2) We have an unqualified name. We search for the given variable
			//     within the highest folder with the valueSetName...
				Folder[] highestFolders = this.metadataDoc.getContainer().getFolderArray() ;	
				for( int i=0; i<highestFolders.length; i++ ) {
					if( highestFolders[i].getName().equals( valueSetName ) ) {
						cursor = highestFolders[i].newCursor() ;
						cursor.selectPath( omrNamespace + "$this//omr:variable[@name='" + variableName + "']" ) ;
						if( cursor.toNextSelection() ) {
							return (Variable)cursor.getObject() ;
						}
						else {
							return null ;
						}
						
					}
				}
				return null ;
			}			
		}
		finally {
			if( cursor != null ) { cursor.dispose() ; }
		}
	}
	
	/**
	 * Forms the start date of an observation fact.
	 * <p/>
	 * <b>This is a critical routine and requires serious examination!</b>
	 * <p/>
	 * <b>NOTES:</b><br/>
	 * (1) If the variable is regarding a Participant entity (eg: age or ethnicity), uses
	 *     Participant.captureStartDate . </br>
	 * (2) If the variable is regarding BloodSamplesCollection or UrineSamplesCollection, uses
	 *     ParticipantTubeRegistration.startTime . </br>
	 * (3) If the variable is regarding Consent, uses startTime as defined within the Consent
	 *     questionnaire. </br>
	 * (4) If the variable is regarding the DataSubmissionQuestionnaire, two possible "specials"
	 *     are allowed for: <br/>
	 * <pre>
	 * i.  If the variable is regarding principle symptoms, we construct a start date from 
	 *     the associated variable start of principle symptoms. Question: What if the start
	 *     date is given as "Unknown"? <b>I don't think this has been covered</b>.
	 * ii. If the variable is regarding an intervention this clinical episode, uses the
	 *     date built into this question. (Requires validation to see whether correct)
	 * </pre>     
	 * (5) If <em><b>ANY</b></em> variables fall through the above net, we fall back upon
	 *     the relevant questionnaire's start time; ie QuestionnaireRun.timeStart . </br>
	 * (6) If we still do not have a date, we report an error. <br/>
	 * (7) If this is a test situation, any date found so far is adjusted to make the date
	 *     non traceable.
	 * 
	 * @param vst
	 * @param value
	 * @return Observation fact start date.
	 */
//	private Calendar _getStartDate( ValueSetType vst, VariableValueType value ) {
//		XmlObject xo = null ;
//		String name = vst.getValueTable() ;
//		
//		if( name.equals( "Participants" ) ) {
//			xo = getValueAsXmlObject( name, "Admin.Participant.captureStartDate" ) ;
//		}
//		else if( name.equals( "BloodSamplesCollection" )
//				 ||
//		         name.equals( "UrineSamplesCollection" ) ) {
//			xo = getValueAsXmlObject( name, "ParticipantTubeRegistration.startTime" ) ;
//		}
//		else if( name.equals( "Consent" )
//				 ||
//				 name.equals( "VerbalConsentQuestionnaire" ) ) {
//			xo = getValueAsXmlObject( "Consent", "startTime" ) ;
//		}
//		else if( name.equals( "DataSubmissionQuestionnaire" ) ) {
//			if( isPrincipleSymptoms( value.getVariable() ) ) {
//				Calendar cal = constructPrincipleSymptomsDate() ;
//				if( cal != null ) {
//					if( OnyxData2Pdo.testFlag == true ) {
//						cal = adjustCalendarForTestRun( cal ) ;
//					}
//					return cal ;
//				}
//				else {
//					log.warn( "Failed to construct principle symptoms onset observation start date!" ) ;
//				}			
//			}
//			else if( isInterventionThisClinicalEpisode( value.getVariable() ) ) {
//				xo = getValueAsXmlObject( name, value.getVariable() ) ;
//			}
//		}
//		
//		if( xo == null ){
//			xo = getValueAsXmlObject( name, "QuestionnaireRun.timeStart" ) ;
//		}
//		
//		if( xo == null ) {
//			log.error( "*** Missing StartDate for: " + vst.getValueTable() + " ***" ) ;
//			xo = getValueAsXmlObject( "Participants", "Admin.Participant.captureStartDate" ) ;
//		}
//
//		Calendar cal = getCorrectedDateTimeValue( xo ) ;
//		if( OnyxData2Pdo.testFlag == true ) {
//			cal = adjustCalendarForTestRun( cal ) ;
//		}
//		return cal ;
//	}
	
	private Calendar getStartDate( ValueSetType vst, VariableValueType value ) {
		if( log.isTraceEnabled() ) enterTrace( "getStartDate()" ) ;
		
		try {
			Calendar calendar = null ;
			//
			// Give the user defined procedure first try at forming a start date...
			calendar = userdefinedStartDate( vst, value ) ;
			if( calendar == null ) {
				DateContextType[] qta = this.configDoc.getOnyxExportConfig().getPdoPhase().getDefaultObservationDates().getDateContextArray() ;
				XmlObject xo = null ;
				String valueTableName = vst.getValueTable() ;
				for( int i=0; i<qta.length; i++ ) {
					if( qta[i].getQuestionnaire().equals( valueTableName ) ) {
						String variableName = qta[i].getStartdate().getVariable() ;							
						xo = getValueAsXmlObject( valueTableName, variableName ) ;
						break ;
					}
					else if( qta[i].isSetAlternateName() ) {
						if( qta[i].getAlternateName().equals( valueTableName ) ) {
							String variableName = qta[i].getStartdate().getVariable() ;							
							xo = getValueAsXmlObject( valueTableName, variableName ) ;
							break ;
						}
					}
				}
				calendar = getCorrectedDateTimeValue( xo ) ;
			}
			if( OnyxData2Pdo.testFlag == true ) {
				calendar = adjustCalendarForTestRun( calendar ) ;
			}
			return calendar ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getStartDate()" ) ;
		}		
	}
	
	private Calendar getEndDate( ValueSetType vst, VariableValueType value ) {
		if( log.isTraceEnabled() ) enterTrace( "getEndDate()" ) ;
		
		try {
			Calendar calendar = null ;
			//
			// Give the user defined procedure first try at forming a start date...
			calendar = userdefinedEndDate( vst, value ) ;
			if( calendar == null ) {
				DateContextType[] qta = this.configDoc.getOnyxExportConfig().getPdoPhase().getDefaultObservationDates().getDateContextArray() ;
				XmlObject xo = null ;
				String valueTableName = vst.getValueTable() ;
				for( int i=0; i<qta.length; i++ ) {
					if( qta[i].getQuestionnaire().equals( valueTableName ) ) {
						String variableName = qta[i].getEnddate().getVariable() ;							
						xo = getValueAsXmlObject( valueTableName, variableName ) ;
						break ;
					}
					else if( qta[i].isSetAlternateName() ) {
						if( qta[i].getAlternateName().equals( valueTableName ) ) {
							String variableName = qta[i].getEnddate().getVariable() ;							
							xo = getValueAsXmlObject( valueTableName, variableName ) ;
							break ;
						}
					}
				}
				calendar = getCorrectedDateTimeValue( xo ) ;
			}
			if( OnyxData2Pdo.testFlag == true ) {
				calendar = adjustCalendarForTestRun( calendar ) ;
			}
			return calendar ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getEndDate()" ) ;
		}		
	}
	
	private Calendar userdefinedStartDate( ValueSetType vst, VariableValueType value ) {
		if( log.isTraceEnabled() ) enterTrace( "userdefinedStartDate()" ) ;
		Calendar calendar = null ;
		try {
			if( this.userDefinedProcess != null ) {
				calendar = this.userDefinedProcess.generateStartDate( vst, value ) ;
			}
			return calendar ;
		}
		finally {
			if( log.isDebugEnabled() ) {
				log.debug( "returning: " + calendar ) ;
			}
			if( log.isTraceEnabled() ) exitTrace( "userdefinedStartDate()" ) ;
		}
	}
	
	private Calendar userdefinedEndDate( ValueSetType vst, VariableValueType value ) {
		if( log.isTraceEnabled() ) enterTrace( "userdefinedEndDate()" ) ;
		Calendar calendar = null ;
		try {
			if( this.userDefinedProcess != null ) {
				calendar = this.userDefinedProcess.generateEndDate( vst, value ) ;
			}
			return calendar ;
		}
		finally {
			if( log.isDebugEnabled() ) {
				log.debug( "returning: " + calendar ) ;
			}
			if( log.isTraceEnabled() ) exitTrace( "userdefinedEndDate()" ) ;
		}
	}
	
	private boolean isNonGeneratedEnumeration( String variableName ) {
		if( log.isTraceEnabled() ) enterTrace( "isNonGeneratedEnumeration()" ) ;
		boolean retCode = false ;
		try {
			if( this.userDefinedProcess != null ) {
				retCode = this.userDefinedProcess.isNonGeneratedEnumeration( variableName ) ;
			}
			return retCode ;
		}
		finally {
			if( log.isDebugEnabled() ) {
				log.debug( "returning: " + retCode ) ;
			}
			if( log.isTraceEnabled() ) exitTrace( "isNonGeneratedEnumeration()" ) ;
		}
	}
	
	/**
	 * Forms the end date of an observation fact.
	 * <p/>
	 * <b>This is a critical routine and requires serious examination!</b>
	 * <p/>
	 * <b>NOTES:</b><br/>
	 * (1) If the variable is regarding a Participant entity (eg: age or ethnicity), uses
	 *     Admin.Participant.captureEndDate . </br>
	 * (2) If the variable is regarding BloodSamplesCollection or UrineSamplesCollection, uses
	 *     ParticipantTubeRegistration.endTime . </br>
	 * (3) If the variable is regarding Consent, uses endTime as defined within the Consent
	 *     questionnaire. </br>
	 * (4) If the variable is regarding the DataSubmissionQuestionnaire, two possible "specials"
	 *     are allowed for: <br/>
	 * <pre>
	 * i.  If the variable is regarding principle symptoms, we construct a end date from 
	 *     the associated variable start of principle symptoms. It means start date and 
	 *     end date are identical in this instance. 
	 *     Question: What if the date is given as "Unknown"? <b>I don't think this has been covered</b>.
	 * ii. If the variable is regarding an intervention this clinical episode, uses the
	 *     date built into this question. (Requires validation to see whether correct)
	 * </pre>     
	 * (5) If <em><b>ANY</b></em> variables fall through the above net, we fall back upon
	 *     the relevant questionnaire's end time; ie QuestionnaireRun.timeEnd . </br>
	 * (6) If we still do not have a date, we report an error. <br/>
	 * (7) If this is a test situation, any date found so far is adjusted to make the date
	 *     non traceable.
	 *     
	 * @param vst
	 * @param value
	 * @return Observation fact end date.
	 */
//	private Calendar _getEndDate( ValueSetType vst, VariableValueType value ) {
//		XmlObject xo = null ;
//		String name = vst.getValueTable() ;
//		
//		if( name.equals( "Participants" ) ) {
//			xo = getValueAsXmlObject( name, "Admin.Participant.captureEndDate" ) ;
//		}
//		else if( name.equals( "BloodSamplesCollection" )
//				 ||
//		         name.equals( "UrineSamplesCollection" ) ) {
//			xo = getValueAsXmlObject( name, "ParticipantTubeRegistration.endTime" ) ;
//		}
//		else if( name.equals( "Consent" )
//				 ||
//				 name.equals( "VerbalConsentQuestionnaire" ) ) {
//			xo = getValueAsXmlObject( "Consent", "endTime" ) ;
//		}
//		else if( name.equals( "DataSubmissionQuestionnaire" ) ) {
//			if( isPrincipleSymptoms( value.getVariable() ) ) {
//				Calendar cal = constructPrincipleSymptomsDate() ;
//				if( cal != null ) {
//					if( OnyxData2Pdo.testFlag == true ) {
//						cal = adjustCalendarForTestRun( cal ) ;
//					}
//					return cal ;
//				}
//				log.warn( "Failed to construct principle symptoms onset observation end date!" ) ;
//			}
//			else if( isInterventionThisClinicalEpisode( value.getVariable() ) ) {
//				xo = getValueAsXmlObject( name, value.getVariable() ) ;
//			}
//		}
//	
//		if( xo == null ){
//			xo = getValueAsXmlObject( name, "QuestionnaireRun.timeEnd" ) ;
//		}
//		
//		if( xo == null ) {
//			log.error( "*** Missing EndDate for: " + vst.getValueTable() + " ***" ) ;
//			xo = getValueAsXmlObject( "Participants", "Admin.Participant.captureEndDate" ) ;
//		}
//		
//		Calendar cal = getCorrectedDateTimeValue( xo ) ;
//		if( OnyxData2Pdo.testFlag == true ) {
//			cal = adjustCalendarForTestRun( cal ) ;
//		}
//		return cal ;
//	}
	
//	private Calendar getEndDate( ValueSetType vst, VariableValueType value ) {
////		if( log.isTraceEnabled() ) enterTrace( "getEndDate()" ) ;
//		
//		try {
//			if( log.isDebugEnabled() ) {
////				log.debug( "ValueSetType:" + vst.getValueTable() ) ;
////				log.debug( "VariableValueType:" + value.getVariable() ) ;
//			}
//			
//			Calendar calendar = null ;
//			DateContextType[] qta = this.configDoc.getOnyxExportConfig().getPdoPhase().getDefaultObservationDates().getDateContextArray() ;
//			XmlObject xo = null ;
//			String valueTableName = vst.getValueTable() ;
//			//
//			// BRICCS specific stuff.
//			// How do I eliminate this?
//			if( valueTableName.equals( "DataSubmissionQuestionnaire" ) ) {
//				if( isPrincipleSymptoms( value.getVariable() ) ) {
//					calendar = constructPrincipleSymptomsDate() ;
//					if( calendar != null ) {
//						if( OnyxData2Pdo.testFlag == true ) {
//							calendar = adjustCalendarForTestRun( calendar ) ;
//						}
//						return calendar ;
//					}
//					else {
//						log.warn( "Failed to construct principle symptoms onset observation end date!" ) ;
//					}			
//				}
//				else if( isInterventionThisClinicalEpisode( value.getVariable() ) ) {
//					xo = getValueAsXmlObject( valueTableName, value.getVariable() ) ;
//				}
//			}
//			if( xo == null ) {
//				//
//				// Generalized stuff, driven by the config file...
//
//				if( valueTableName.contains( "Participant" ) ) {
////					log.debug( "Participant" ) ;
//				}
//				for( int i=0; i<qta.length; i++ ) {
//					if( qta[i].getQuestionnaire().equals( valueTableName ) ) {
//						String variableName = qta[i].getEnddate().getVariable() ;							
//						xo = getValueAsXmlObject( valueTableName, variableName ) ;
//						break ;
//					}
//					else if( qta[i].isSetAlternateName() ) {
//						if( qta[i].getAlternateName().equals( valueTableName ) ) {
//							String variableName = qta[i].getEnddate().getVariable() ;							
//							xo = getValueAsXmlObject( valueTableName, variableName ) ;
//							break ;
//						}
//					}
//				}
//			}
//
//			calendar = getCorrectedDateTimeValue( xo ) ;
//			if( OnyxData2Pdo.testFlag == true ) {
//				calendar = adjustCalendarForTestRun( calendar ) ;
//			}
//			return calendar ;
//		}
//		finally {
////			if( log.isTraceEnabled() ) exitTrace( "getEndDate()" ) ;
//		}
//		
//	}

	/**
	 * Utility routine that ensures XML date format is correct, or logs an error.
	 * <p/>
	 * Delegates to another function.
	 * 
	 * @param xmlFragment
	 * @return corrected datetime 
	 */
	private Calendar _getCorrectedDateTimeValue( String xmlFragment ) {
		if( xmlFragment != null ) {
			try {
				xmlFragment = getCorrectedDateTimeValueFromString( xmlFragment ) ;
				XmlDateTime dateTime = XmlDateTime.Factory.parse( xmlFragment ) ;
				return dateTime.getCalendarValue() ;
			}
			catch( XmlException xe ) {
				log.error( "Failed to parse date: " + xmlFragment ) ;
			}
		}
		return null ;
	}
	
	/**
	 * Utility routine that ensures XML date format is correct, or logs an error.
	 * <p/>
	 * Delegates to another function.
	 * 
	 * @param xmlFragment
	 * @return corrected datetime 
	 */
	protected Calendar getCorrectedDateTimeValue( XmlObject xo ) {
		if( xo != null ) {
			try {
				String correctedDate = getCorrectedDateTimeValueFromString( getText( xo ) ) ;
				XmlDateTime dateTime = XmlDateTime.Factory.parse( FRAGMENT_START + correctedDate + FRAGMENT_END ) ;
				return dateTime.getCalendarValue() ;
			}
			catch( XmlException xe ) {
				log.error( "Failed to parse date: " + getText( xo ) ) ;
			}
		}
		return null ;
	}
	
	/**
	 * The Onyx questionnaire tend to put the date (standard time adjustment)
	 * in a form not readily acceptable as an XMLDateTime format.
	 * This makes a suitable adjustment.
	 * 
	 * <p><blockquote><pre>
	 * From:
	 * 2010-07-21T15:10:44.000+0100
	 * 2010-07-21T15:10:44.000-0100
	 * 2011-01-05T16:48:06.000+0000
	 * 2005-11-03T16:38:04.000
	 * 
	 * To:
	 * 2010-07-21T15:10:44.000+01:00
	 * 2010-07-21T15:10:44.000-01:00
	 * 2011-01-05T16:48:06.000+00:00
	 * 2005-11-03T16:38:04.000+00:00
     * </pre></blockquote><p> 
	 * 
	 * @param value
	 * @return corrected datatime as an XML fragment.
	 */
	protected String getCorrectedDateTimeValueFromString( String value ) {
		if( log.isTraceEnabled() ) enterTrace( "getCorrectedDateTimeValueFromString()" ) ;
		try {
			if( value == null ) {
				log.debug( "null value for date or datetime" ) ;
				return null ;
			}
			// 2012-10-2
			StringBuilder b = new StringBuilder() ;
			//
			// We only accept DATETIME formats.
			// The ontology transposes DATE type to DATETIME, so here
			// we correspondingly adjust values;
			// ie: we adjust any plain dates to be DATETIME
			// eg: 2012-10-2 will become 2012-10-02T00:00:00.000+00:00
			if( value.length() <= "ccyy-mm-dd".length() ) {
				// divide into years, months and days...
				String[] parts = value.split( "-" ) ;
				// append the years...
				b.append( parts[0]).append( '-' ) ; 
				// append the months...
				if( parts[1].length() == 1 ) {
					b.append( "0" ) ;
				}
				// append the days...
				b.append( parts[1] ).append( '-' ) ;
				if( parts[2].length() == 1 ) {
					b.append( "0" ) ;
				}
				b.append( parts[2] ) ;
				// append a dummy time and time zone...
				b.append( "T00:00:00.000+00:00" ) ;
				log.debug( "Adjusted date value from [" + value + "] to [" + b.toString() + "]" ) ;
				return b.toString() ;
			}
			//
			// If some timezone was expressed (ie: +nnnn or -nnnn)
			// we add the necessary colon (ie: +nn:nn or -nn:nn)...
			if( value.matches( TIMEZONE_REGEX ) ) {
				int index = value.lastIndexOf( "+" ) ;
				if( index == -1 ) {
					index = value.lastIndexOf( "-" ) ;
				}
				b.append( value.substring( 0,index+3 ) )
				 .append( ":" )
				 .append( value.substring( index+3 ) ) ;
			}
			//
			// If there was not timezone, we add a dummy
			// (Kludge for the sake of SqlServer)...
			else {
				b.append( value )
				 .append( "+00:00" ) ;
			}
			log.debug( "Adjusted datetime value from [" + value + "] to [" + b.toString() + "]" ) ;
			return b.toString() ;	
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getCorrectedDateTimeValueFromString()" ) ;
		}		
	}

	
	/**
	 * @return enrollment id or (if a test run) generate a suitable one.
	 */
	protected String getEnrollmentId() {	
		if( this.enrollmentId == null ) {
			if( OnyxData2Pdo.testFlag == true ) {
				this.enrollmentId = generateEnrollmentId() ;
			}
			else {
				this.enrollmentId = getValue( "Participants", "Admin.Participant.enrollmentId" ) ;
			}
		}
		return this.enrollmentId ;
	}
	
	private String generateEnrollmentId() {		
		StringBuilder sb = new StringBuilder() ;
		Formatter formatter = new Formatter( sb, Locale.US ) ;		
		formatter.format( OnyxData2Pdo.idPrefix + "%1$09d" , new Object[] { new Long(OnyxData2Pdo.idNum) } );
		OnyxData2Pdo.idNum++ ;
		return sb.toString() ;
	}
	
	
//	protected String getEnrollmentId() {		
//		if( OnyxData2Pdo.testFlag == true ) {
//			return "@" ;
//		}
//		return getValue( "Participants", "Admin.Participant.enrollmentId" ) ;
//	}

	/**
	 * The date is extracted from Admin.Participant.birthdate.
	 * <p>
	 * If this is a test run, alters the date in a systematized but random way
	 * for each participant.
	 * 
	 * @return A participant's birthdate as a string in XML dateTime format
	 */
	private String getBirthDate() {	
		ValueType vtBirthdate = getValueAsXmlObject( "Participants", "Admin.Participant.birthDate" ) ;
		Calendar b = getCorrectedDateTimeValue( vtBirthdate ) ; 
		
		if( OnyxData2Pdo.testFlag == true ) {	
			b = adjustCalendarForTestRun( b ) ;
		}
		
		XmlDateTime xdt = XmlDateTime.Factory.newInstance() ;
		xdt.setCalendarValue( b ) ;
		String birthdate = getText( xdt ) ;
		return birthdate ;
	}
	
	private String getDateTime( XmlObject valueAsXmlObject ) {	
		Calendar b = getCorrectedDateTimeValue( valueAsXmlObject ) ; 
		
		if( OnyxData2Pdo.testFlag == true ) {	
			b = adjustCalendarForTestRun( b ) ;
		}
		
		XmlDateTime xdt = XmlDateTime.Factory.newInstance() ;
		xdt.setCalendarValue( b ) ;
		String birthdate = getText( xdt ) ;
		return birthdate ;
	}
	
	/**
	 * @param cal
	 * @return Given calendar adjusted for a test run
	 */
	protected Calendar adjustCalendarForTestRun( Calendar cal ) {
		cal.set( Calendar.YEAR, cal.get( Calendar.YEAR ) - this.testYearAdjustment ) ;
		cal.set( Calendar.MONTH, this.testMonth ) ;
		cal.set( Calendar.DAY_OF_MONTH, this.testDay ) ;
		return cal ;
	}
	
	/**
	 * @return Participant's age as a string.
	 */
	private String getAge( ValueType value ) {
		String age = getText( value ) ;
		if( OnyxData2Pdo.testFlag == true ) {	
			try {
				int iAge = Integer.valueOf( age ) + this.testYearAdjustment ;
				age = String.valueOf( iAge ) ;
			}
			catch( NumberFormatException nfx ) {
				log.error( "Failed to adjust Age for test run.", nfx ) ;
			}			
		}
		return age ;
	}
	
	/**
	 * @return Participant's gender as a string.
	 */
	private String getGender() {
		return getValue( "Participants", "Admin.Participant.gender" ) ; 
	}
	
	
	/**
	 * @return Participant's ethnicity as a string.
	 */
	private String getEthnicity() {
		return getValue( "Participants", "Admin.Participant.pat_ethnicity" ) ;
	}
	
	@SuppressWarnings("unused")
	private String getParticipantId() {
		ValueSetDocument vsd = getValueSet( "Participants" ) ;
		return vsd.getValueSet().getEntityIdentifier() ;
	}
	
	/**
	 * @return Participant's recruitment code as a string.
	 */
	private String getRecruitmentCode() {
		return getValue( "Participants", "Admin.Participant.recruitmentType" ) ;
	}
	
	/**
	 * @param valueSetName
	 * @param variableName
	 * @return The element value of the named variable within the given value set.
	 */
	protected String getValue( String valueSetName, String variableName ) {
		if( log.isTraceEnabled() ) enterTrace( "getValue()" ) ;
		try {
			ValueType vt = getValueAsXmlObject( valueSetName, variableName ) ;
			if( vt == null ) {
				return null ;
			}
			return getText( vt ) ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getValue()" ) ;
		}
	}
	
	/**
	 * @param valueSetName
	 * @param variableName
	 * @return The XML fragment of the the named value within the given value set.
	 */
	private String getValueAsFragment( String valueSetName, String variableName ) {
		if( log.isTraceEnabled() ) enterTrace( "getValueAsFragment()" ) ;
		try {
			ValueType vt = getValueAsXmlObject( valueSetName, variableName ) ;
			if( vt == null ) {
				return null ;
			}
			return vt.toString() ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getValueAsFragment()" ) ;
		}
	}
	
	/**
	 * @param valueSetName
	 * @param variableName
	 * @return The XML object for the the named value within the given value set.
	 */
	protected ValueType getValueAsXmlObject( String valueSetName, String variableName ) {
		if( log.isTraceEnabled() ) enterTrace( "getValueAsXmlObject()" ) ;
		try {
			ValueSetDocument vsd = getValueSet( valueSetName ) ;
			if( vsd == null ) {
				return null ;
			}
			VariableValueType[] vvta = vsd.getValueSet().getVariableValueArray() ;
			for( int i=0; i<vvta.length; i++ ) {
				if( vvta[i].getVariable().equals( variableName ) ) {
					 return vvta[i].getValue() ;
				}
			}
			return null ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getValueAsXmlObject()" ) ;
		}
	}
	
	/**
	 * @param name
	 * @return The document for the named value set.
	 */
	private ValueSetDocument getValueSet( String name ) {
		if( log.isTraceEnabled() ) enterTrace( "getValueSet()" ) ;
		try {
			for( int i=0; i<this.dataDocs.length; i++ ) {
				if( dataDocs[i].getValueSet().getValueTable().equals( name ) ) {
					return dataDocs[i] ;
				}
			}
			return null ;
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "getValueSet()" ) ;
		}
	}
	
	/**
	 * Saves the current PDO object as an XML file.
	 * <p/>
	 * Depending upon whether this is a test run, the format of the file name is either: <br/>
	 * (i)  onyx-{processedCount}-yyyMMdd-HHmmssSSS-pdo.xml, or: <br/>
	 * (ii) onyx-{processedCount}-yyyMMdd-HHmmssSSS-TEST-DATA-ONLY-pdo.xml
	 * <p/>
	 * where {processedCount} is the number of participants included within the file.
	 * 
	 * @param processedCount
	 * @throws ProcessException
	 */
	private void savePatientDataObject( int processedCount ) throws ProcessException {
		if( log.isTraceEnabled() ) enterTrace( "savePatientDataObject()" ) ;
		StringBuilder b = new StringBuilder() ;
		SimpleDateFormat df = new SimpleDateFormat( "yyyyMMdd-HHmmssSSS" ) ;
		b.append( i2b2DataDirectory.getAbsolutePath() )
		 .append( System.getProperty( "file.separator" ) ) 
		 .append( "onyx-")
		 .append( processedCount )
		 .append( "-" )
		 .append( df.format( new Date() ) ) ;		
		if( OnyxData2Pdo.testFlag == true ) {
			b.append( "-TEST-DATA-ONLY" ) ;
		}
		b.append( "-pdo.xml" ) ;
		String fullPath = b.toString() ;
		log.debug( "fullPath: " + fullPath ) ;
		try {		
			XmlOptions opts = getSaveOptions() ;
			pdoDoc.save( new File( fullPath ), opts ) ;
			pdoDoc = null ;
		}
		catch( Exception iox ) {
			String message = "Save pdo file failed: " + fullPath ;
			throw new ProcessException( message, iox ) ;			
		}
		finally { 
			if( log.isTraceEnabled() ) exitTrace( "savePatientDataObject()" ) ;
		}
	}
	
    /**
     * Returns the <code>XmlOptions</code> required to produce
     * a text representation of the emitted XML.
     * 
     * @return XmlOptions
     */
    private XmlOptions getSaveOptions() {
        XmlOptions opts = new XmlOptions();
        opts.setSaveOuter() ;
        opts.setSaveNamespacesFirst() ;
        opts.setSaveAggressiveNamespaces() ;  
        
        HashMap<String, String> suggestedPrefixes = new HashMap<String, String>();
        suggestedPrefixes.put("http://www.i2b2.org/xsd/hive/pdo/1.1/pdo", "pdo");
        opts.setSaveSuggestedPrefixes(suggestedPrefixes);
              
        opts.setSavePrettyPrint() ;
        opts.setSavePrettyPrintIndent( 3 ) ; 
        return opts ;
    }
    
    public static String generateHash( String variableName ) {
    	String retVal = null ;
    	try {
    		byte[] variableBytes = variableName.getBytes( "UTF-8" ) ;
        	MessageDigest md = MessageDigest.getInstance( "MD5" ) ;
        	byte[] digest = md.digest( variableBytes ) ;
        	retVal = Hex.encodeHexString( digest ) ;
    	}
    	catch( UnsupportedEncodingException ucx ) {
    		log.error( "Could not generate hash for variable name.", ucx ) ;
    	}
    	catch( NoSuchAlgorithmException nsax ) {
    		log.error( "Could not generate hash for variable name.", nsax ) ;
    	}   
    	return retVal ;
    }
	
	/**
	 * Utility routine to enter a structured message in the trace log that the given method 
	 * has been entered. Almost essential for syntax debugging.
	 * 
	 * @param entry: the name of the method entered
	 */
	public static void enterTrace( String entry ) {
		log.trace( getIndent().toString() + "enter: " + entry ) ;
		indentPlus() ;
	}

    /**
     * Utility routine to enter a structured message in the trace log that the given method 
	 * has been exited. Almost essential for syntax debugging.
	 * 
     * @param entry: the name of the method exited
     */
    public static void exitTrace( String entry ) {
    	indentMinus() ;
		log.trace( getIndent().toString() + "exit : " + entry ) ;
	}
	
    /**
     * Utility method used to maintain the structured trace log.
     */
    public static void indentPlus() {
		getIndent().append( ' ' ) ;
	}
	
    /**
     * Utility method used to maintain the structured trace log.
     */
    public static void indentMinus() {
        if( logIndent.length() > 0 ) {
            getIndent().deleteCharAt( logIndent.length()-1 ) ;
        }
	}
	
    /**
     * Utility method used for indenting the structured trace log.
     */
    public static StringBuffer getIndent() {
	    if( logIndent == null ) {
	       logIndent = new StringBuffer() ;	
	    }
	    return logIndent ;	
	}
    
    @SuppressWarnings("unused")
	private static void resetIndent() {
        if( logIndent != null ) { 
            if( logIndent.length() > 0 ) {
               logIndent.delete( 0, logIndent.length() )  ;
            }
        }   
    }
    
    /**
     * @return The next i2b2 patient number.
     */
//    private int nextPatientNum() {
//    	return ++patientNum ;
//    }
    
//    /**
//     * @return The next i2b2 encounter number.
//     */
//    private int nextEncounterNum() {
//    	return ++encounterNum ;
//    }
//    
    /**
     * @return The current i2b2 patient number.
     */
    protected abstract String getCurrentPatientNum() ;
    
    protected abstract String getCurrentPatientSource() ;
    
    /**
     * @return The current i2b2 encounter number.
     */
    protected abstract String getCurrentEncounterNum() ;
    
    protected abstract String getCurrentEncounterSource() ;
    
    
    protected abstract String getCurrentObserverSource() ;
    
    protected abstract String getCurrentObserverNum() ;
       
    /**
     * Utility class covering all exceptions thrown by the Factory object.
     * 
     * @author jl99
     *
     */
    public static class FactoryException extends Exception {
    	
    	/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		public FactoryException( String message ) {
    		super( message ) ;
    	}
    	
    	public FactoryException( String message, Throwable cause ) {
    		super( message, cause ) ;
    	}
    	
    }
    
    /**
     * Utility class covering all exceptions possibly thrown whilst producing the PDO.
     * 
     * @author jl99
     *
     */
    public static class ProcessException extends Exception {
    	
    	/**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		public ProcessException( String message ) {
    		super( message ) ;
    	}
    	
    	public ProcessException( String message, Throwable cause ) {
    		super( message, cause ) ;
    	}
    	
    }
    
    protected static class TestParticipantHolder {
    	
    	String enrollmentId ; 
    	String gender ; 
    	String birthdate ;
    	
    	protected TestParticipantHolder( String enrollmentId, String gender, String birthdate ) {
    		this.enrollmentId = enrollmentId ;
    		this.gender = gender ;
    		this.birthdate = birthdate ;  				
    	}
    	
    	@SuppressWarnings("unused")
		private TestParticipantHolder() {}
    	
    	public String toString() {
    		StringBuilder sb = new StringBuilder() ;
    		sb.append( "enrollmentId: [" )
    		  .append( this.enrollmentId )
    		  .append( "] gender: [" )
    		  .append( this.gender )
    		  .append( "] birthdate: [" )
    		  .append( this.birthdate )
    		  .append( "]" ) ;
    		return sb.toString() ;
    	}
    	
    	
    }
   
    /**
     * The <code>OnyxData2Pdo.Factory</code> class takes on the task of vetting command
     * line parameters and construction of the main process object prior to execution.
     * 
     * @author  Jeff Lusted jl99@le.ac.uk
     *
     */
    public static class Factory {
    	
    	/**
    	 * Creates a new instance of the main process object <code>OnyxData2Pdo</code>.
    	 * <p/>
    	 * <b>Notes:</b> <br/>
    	 * 1. If this is a test run, sets the calendar date to 5th August 1988. <br/>
    	 * 2. Parses the main metadata file. <br/>
    	 * 3. Checks the Onyx export directories to ensure that the entity files for 
    	 *    each participant match up across the questionnaire directories. <br/>
    	 * 4. Creates the output directory. <br/>
    	 * 
    	 * @return the main process object
    	 * @throws FactoryException
    	 */
    	public static OnyxData2Pdo newInstance() throws FactoryException {
    		if( log.isTraceEnabled() ) enterTrace( "OnyxData2Pdo.Factory.newInstance()" ) ;
    		OnyxData2Pdo od2p = null ;
    		//
    		// Create our application object...
    		if( OnyxData2Pdo.useWebService == true ) {
    			od2p = new OnyxData2PdoWs() ;
    		}
    		else {
    			od2p = new OnyxData2PdoSql( patientNum, encounterNum ) ;
    		}    		
    		//
    		// Set up a calendar for the admin dates group
    		od2p.adminCalendar = Calendar.getInstance() ;
    		//
    		// But override it if the run is for test data only
    		// ( 5th August 1988 is my son's birthday)...
    		if( testFlag == true ) {
    			od2p.adminCalendar.set( 1988, 7, 5 ) ;	
    		}
    		//
    		// Set up the ontology type...
    		//
    		if( ontologyType == null ) {
    			od2p.bNominalOntology = false ;
    		}
    		else if( ontologyType.equalsIgnoreCase( "real") ) {
    			od2p.bNominalOntology = false ;
    		}
    		else {
    			od2p.bNominalOntology = true ;
    		}
    		// Parse the config file...
    		try {
    			od2p.configDoc = OnyxExportConfigDocument.Factory.parse( configFile ) ;
    			od2p.pdoPhase = od2p.configDoc.getOnyxExportConfig().getPdoPhase() ;
    			od2p.nominalCodePrefix = od2p.configDoc.getOnyxExportConfig().getCodePrefix() ;
    			od2p.systemAcroymn = od2p.pdoPhase.getOrganization().getSystemAcronym() ;
    			od2p.projectAcronymn = od2p.pdoPhase.getOrganization().getProjectAcronym() ;
    			if( od2p.pdoPhase.isSetPatientDimension() ) {
    				OnyxData2Pdo.includePatientDim = true ;
    			}
    			else {
    				OnyxData2Pdo.includePatientDim = false ;
    			}
    		}
    		catch( IOException iox ) {
    			throw new FactoryException( "Something wrong with config file.", iox ) ;
    		}
    		catch( XmlException xmlx ) {
    			throw new FactoryException( "Could not parse configuration file.", xmlx ) ;   			
    		}
    		//
    		// Parse the main metadata file...
    		try {
    			od2p.metadataDoc = ContainerDocument.Factory.parse( mainMetadataFile ) ;
    		}
    		catch( IOException iox ) {
    			throw new FactoryException( "Something wrong with metadata file.", iox ) ;
    		}
    		catch( XmlException xmlx ) {
    			throw new FactoryException( "Could not parse metadata file.", xmlx ) ;   			
    		}
    		//
    		// Check all the entities marry up across the different directories in the Onyx export...
    		checkEntities( od2p ) ;
    		//
    		// Build the collection of ontological variables...
    		if( !od2p.bNominalOntology ) {
    			buildOntologicalVariablesCollection( od2p ) ;
    		}		
    		//
    		// Everything seems in place!
    		// Now is a good time to create the output directory...
    		if( i2b2DataDirectory.mkdirs() == false ) {
    			throw new FactoryException( "Could not create PDO directory: " + i2b2DataDirectory.getAbsolutePath() ) ;
    		}
    		
    		if( od2p.pdoPhase.isSetUserDefinedProcedure() ) {
    			
    			String fullPackageName = null ;
    			try {
    				fullPackageName = od2p.pdoPhase.getUserDefinedProcedure().trim() ;
    				Class clzz =  Class.forName( fullPackageName ) ;		
    				od2p.userDefinedProcess = (IExport2Pdo)clzz.newInstance() ; 
    				od2p.userDefinedProcess.setOnyxData2Pdo( od2p ) ;
    			}
    			catch( Exception ex ) {
    				log.error( "Could not instantiate user defined procedure: " + fullPackageName, ex ) ;
    			}
    			
    		}
    		
    		if( od2p.testFlag ) {
    			od2p.testParticipants = new ArrayList<TestParticipantHolder>() ;
    		}
    		
    		if( log.isTraceEnabled() ) exitTrace( "OnyxData2Pdo.Factory.newInstance()" ) ;
    		return od2p ;
    	}
    	
    	/**
    	 * @param args
    	 * @return true if all the mandatory arguments were entered, false otherwise.
    	 */
    	private static boolean retrieveArgs( String[] args ) {
            boolean retVal = false ;
            int testFlag = -99 ;
            if( args != null && args.length > 0 ) {
                
                for( int i=0; i<args.length; i++ ) {
                    
                	if( args[i].startsWith( "-export=" ) ) { 
                        OnyxData2Pdo.exportDir = args[i].substring(8) ;
                    }
                    else if( args[i].startsWith( "-refine=" ) ) { 
                    	OnyxData2Pdo.refineDir = args[i].substring(8) ;
                    }
                    else if( args[i].startsWith( "-enum=" ) ) { 
                    	OnyxData2Pdo.enumDir = args[i].substring(6) ;
                    }
                    else if( args[i].startsWith( "-ontology=" ) ) { 
                    	OnyxData2Pdo.ontologyType = args[i].substring(10).trim();
                    }
                    else if( args[i].startsWith( "-config=" ) ) { 
                    	OnyxData2Pdo.configFilePath = args[i].substring(8) ;
                    }
                    else if( args[i].startsWith( "-pdo=" ) ) { 
                    	OnyxData2Pdo.pdoDir = args[i].substring(5) ;
                    }            	
                    else if( args[i].startsWith( "-name=" ) ) { 
                    	OnyxData2Pdo.mainMetadataFileName = args[i].substring(6) ;
                    }
                    else if( args[i].startsWith( "-batch=" ) ) { 
                    	try {
                    		OnyxData2Pdo.batchSize = Integer.valueOf( args[i].substring(7).trim() ) ;
                    	}
                    	catch( NumberFormatException nfx ) {
                    		OnyxData2Pdo.batchSize = -1 ;
                    		log.error( "-batch argument is incorrect: " + args[i] ) ;
                    	}
                    }
                    else if( args[i].startsWith( "-test=" ) ) { 
                    	String param = args[i].substring( args[i].indexOf( "=" )+1 ).trim() ;
                    	if( param.equalsIgnoreCase( "yes" ) || param.equalsIgnoreCase( "y" ) ) {
                    		testFlag = 1 ;
                    	}
                    	else if( param.equalsIgnoreCase( "no" ) || param.equalsIgnoreCase( "n" ) ) {
                    		testFlag = 0 ;
                    	}
                    	else {
                    		testFlag = -1 ;
                    		log.error( "-test argument is incorrect: " + args[i] ) ;
                    	}
                    }
                    else if( args[i].startsWith( "-bid=" ) ) {
                    	String param = args[i].substring( args[i].indexOf( "=" )+1 ).trim() ;
                		String[] parts = param.split( "-" ) ;
                		if( parts.length != 2 ) {
                			log.error( "-bid argument is incorrect: " + args[i] ) ;
                			return retVal ;
                		}
                		else {
                			OnyxData2Pdo.idPrefix = parts[0] + "-" ; 
                			try {
                				OnyxData2Pdo.idNum = Integer.valueOf( parts[1] ) ;
                			}
                			catch( NumberFormatException nfx ) {
                				log.error( "-bid argument is incorrect: " + args[i] ) ;
                				return retVal ;
                			}
                		}
                	}
                    else if( args[i].startsWith( "-pid=" ) ) { 
                    	try {
                    		useWebService = false ;
                    		OnyxData2Pdo.patientNum = Integer.valueOf( args[i].substring(5).trim() ) ;
                    		//
                    		// We do not allow zero...
                    		if( OnyxData2Pdo.patientNum == 0 ) {
                    			OnyxData2Pdo.patientNum = -1 ;
                    			log.error( "-pid argument must be a positive integer." ) ;
                    		} 
                    	}
                    	catch( NumberFormatException nfx ) {
                    		OnyxData2Pdo.patientNum = -1 ;
                    		log.error( "-pid argument is incorrect: " + args[i] ) ;
                    	}
                    }
                    else if( args[i].startsWith( "-eid=" ) ) { 
                    	try {
                    		useWebService = false ;
                    		OnyxData2Pdo.encounterNum = Integer.valueOf( args[i].substring(5).trim() ) ;
                    		//
                    		// We do not allow zero...
                    		if( OnyxData2Pdo.encounterNum == 0 ) {
                    			OnyxData2Pdo.encounterNum = -1 ;
                    			log.error( "-eid argument must be a positive integer." ) ;
                    		}
                    	}
                    	catch( NumberFormatException nfx ) {
                    		OnyxData2Pdo.patientNum = -1 ;
                    		log.error( "-eid argument is incorrect: " + args[i] ) ;
                    	}
                    }
                    else {
                    	log.error( "Unknown argument: " + args[i] ) ;
                    	outputArgs( args ) ;
                    	return retVal ;
                    }
                   
                } // end for
                if( OnyxData2Pdo.exportDir != null 
                	&& 
                	OnyxData2Pdo.refineDir != null
                	&&
                	OnyxData2Pdo.enumDir != null
                	&&
                	OnyxData2Pdo.configFilePath != null
                	&&
                	OnyxData2Pdo.pdoDir != null
                	&&
                	OnyxData2Pdo.mainMetadataFileName != null 
                	&&
                	OnyxData2Pdo.batchSize > 0 
                	&&
                	testFlag != -1
                	&&
                	//
                	// We are either using the web service route, or have valid patient and encounter numbers...
                	( ( OnyxData2Pdo.patientNum > 0 && OnyxData2Pdo.encounterNum > 0 ) || useWebService == true ) ) {
                	
                	OnyxData2Pdo.testFlag = ( testFlag == 1 ? true : false ) ;
                	if( useWebService == false ) {
                		//
                		// Decrements to allow for the first getnext number (otherwise we get a gap) ...
                		OnyxData2Pdo.patientNum-- ;
                		OnyxData2Pdo.encounterNum-- ;
                	}
                	retVal = true ;
                }
                else {
                	outputArgs( args ) ;
                }
            }       
            return retVal ;
        }
    	
    	private static void outputArgs( String[] args ) {
    		StringBuilder b = new StringBuilder( 1024 ) ;
        	b.append( "Arguments entered for OnyxData2Pdo were:\n" ) ;
        	for( int i=0; i<args.length; i++ ) {
        		b.append( args[i] ).append( "\n" ) ;
        	}               	
        	log.error( b.toString() ) ;
    	}
    	
    	/**
    	 * Some basic checks on the command line arguments.
    	 * 
    	 * @throws FactoryException
    	 */
    	private static void levelTwoChecksAgainstCommandLineArgs() throws FactoryException {
    		//
    		// Check test parameters if required...
    		if( OnyxData2Pdo.testFlag ) {
    			if( OnyxData2Pdo.idPrefix == null || OnyxData2Pdo.idNum == -1 ) {
    				throw new FactoryException( "-bid=aaaa-nnnnn parameter is required for a test run.\n" ) ;
    			}
    		}
    		//
    		// Check export directory exists...
    		exportDirectory = new File( exportDir );
    		if( !exportDirectory.isDirectory() ) {
    			throw new FactoryException( "Export directory does not exist: " + exportDirectory + "\n" ) ;
    		}
    		//
    		// Check metadata directory exists...
    		refineDirectory = new File( refineDir ) ;
    		if( !refineDirectory.isDirectory() ) {
    			throw new FactoryException( "Refined metadata directory does not exist: " + refineDir + "\n" ) ;
    		} 
    		//
    		// Check enum metadata directory exists...
    		enumDirectory = new File( enumDir ) ;
    		if( !enumDirectory.isDirectory() ) {
    			throw new FactoryException( "Enumerations metadata directory does not exist: " + enumDir + "\n" ) ;
    		}
    		//
    		// Check ontology type is real or nominal...
    		if( ontologyType != null ) {
    			if( !ontologyType.equalsIgnoreCase( "real" )
    				&&
    		        !ontologyType.equalsIgnoreCase( "nominal" )) {
    				throw new FactoryException( "Ontology must be \"real\" or \"nominal\": " + ontologyType + "\n" ) ;
    			}
    		}
    		//
    		// Check config file exists...
    		configFile = new File( configFilePath ) ;
    		if( !configFile.isFile() ) {
    			throw new FactoryException( "Config file does not exist: " + configFilePath + "\n" ) ;
    		}
    		//
    		// Check PDO directory does not exist...
    		i2b2DataDirectory = new File( pdoDir ) ;
    		if( i2b2DataDirectory.exists() ) {
    			throw new FactoryException( "PDO directory already exists: " + pdoDir + "\n" ) ;
    		}
    		//
    		// Check main metadata file exists...
    		mainMetadataFile = new File( refineDir + File.separator + mainMetadataFileName ) ;
    		if( !mainMetadataFile.exists() ) {
    			throw new FactoryException( "Could not find main metadata file: " + mainMetadataFile.getAbsolutePath() + "\n" ) ;
    		}
    	}
    	
    	/**
    	 * Each leg of the Onyx questionnaire has a list of data files, one per participant.
    	 * To process a list of participants, these must be matched up; ie: one participant is 
    	 * represented by a list of files. This routine ensures they match up.
    	 * 
    	 * @param od2bp
    	 * @throws FactoryException
    	 */
    	private static void checkEntities( OnyxData2Pdo od2bp ) throws FactoryException {
    		if( log.isTraceEnabled() ) enterTrace( "OnyxData2Pdo.Factory.checkEntities()" ) ;
    		File[] children = exportDirectory.listFiles() ; 
    		
    		for( int i=0; i<children.length; i++ ) {
    			if( children[i].isDirectory() ) {
    				File[] files = children[i].listFiles() ;
    				for( int j=0; j<files.length; j++ ) {
    					if( files[j].getName().equalsIgnoreCase( "entities.xml" ) ) {
    						EntitiesDocument eDoc = null ;
    						try {
    							eDoc = EntitiesDocument.Factory.parse( files[j] ) ;
    							log.debug( "Parsed file; " + files[j].getAbsolutePath() ) ;
    							EntryType[] eta = eDoc.getEntities().getMap().getEntryArray() ;
    							if( od2bp.participants == null) {
    								od2bp.participants = new LinkedHashMap<String, String>(500) ;
    								for( int k=0; k<eta.length; k++ ) {
    									od2bp.participants.put( eta[k].getStringArray(0), eta[k].getStringArray(1) ) ;
    								}
    							}
    							else {
    								if( od2bp.participants.size() != eta.length ) {
    									throw new FactoryException( "Number of participants do not match. Found in directory: " + children[i].getName() ) ;
    								}
    								for( int k=0; k<eta.length; k++ ) {
    									if( !od2bp.participants.containsKey( eta[k].getStringArray(0) ) ) {
    										throw new FactoryException( "Miss match on " + eta[k].getStringArray(0) + ". Found in directory: " + children[i].getName() ) ;
    									}
    									String one = od2bp.participants.get( eta[k].getStringArray(0) ) ;
    									String two = eta[k].getStringArray(1) ;
    									if( !one.equals(two) ) {
    										throw new FactoryException( "Miss match on " + eta[k].getStringArray(1) + ". Found in directory: " + children[i].getName()) ;
    									}
    								}
    							}
    						}
    						catch( IOException e ) {
    							throw new FactoryException( "Something wrong with file: " + files[j].getAbsolutePath(), e ) ;
    						}
    						catch( XmlException x ) {
    							throw new FactoryException( "Something wrong with file: " + files[j].getAbsolutePath(), x ) ;
    						}
    					} 
    				}
    			}
    		}
    		if( log.isDebugEnabled() && od2bp.participants != null ) {
				StringBuilder b = (new StringBuilder()).append( "Following participants to be processed:\n" ) ;
				Set<String> keys = od2bp.participants.keySet() ;
				Iterator<String> it = keys.iterator() ;
				while( it.hasNext() ) {
					b.append( it.next().toString() ).append( "\n" ) ;
				}
				log.debug( b.toString() ) ;
			}
    		if( log.isTraceEnabled() ) exitTrace( "OnyxData2Pdo.Factory.checkEntities()" ) ;
    	}
    	
    	/**
    	 * Using the refined metadata document for a real (ie: a research) ontology, builds up a collection 
    	 * of metadata on variables keyed on their ontological code. 
    	 * 
    	 * This will be used to search for metadata on a variable.
    	 * 
    	 * @param od2p
    	 * @throws FactoryException
    	 */
    	private static void buildOntologicalVariablesCollection( OnyxData2Pdo od2p ) throws FactoryException {
    		if( log.isTraceEnabled() ) enterTrace( "OnyxData2Pdo.Factory.buildOntologicalVariablesCollection()" ) ;
    		od2p.ontologicalVariables = new HashMap<String, XmlObject>( 2048 ) ;  		
    		//
    		// XmlCursor can search through a complete document, token by token (very low level).
    		XmlCursor cursor = od2p.metadataDoc.newCursor() ;
    		
    		try {
    			//
    			// Go through every token...
    			while( cursor.hasNextToken() ) {
    				cursor.toNextToken() ;
    				//
    				// We're only interested in xml elements...
    				if( cursor.isStart() ) {
    					XmlObject xo = cursor.getObject() ;
    					//
    					// We're only interested in elements that are variables or folders...
    					if( xo instanceof Variable ) {
    						Variable v = (Variable)xo ;
    						od2p.ontologicalVariables.put( v.getCode(), v ) ;
    						if( log.isDebugEnabled() ) {
    							log.debug( "Put variable code: " + v.getCode() ) ;
    						}
    					}
    					else if( xo instanceof Folder ) {
    						Folder f = (Folder)xo ;
    						if( f.isSetCode() ) {
    							od2p.ontologicalVariables.put( f.getCode(), f ) ;
    							if( log.isDebugEnabled() ) {
    								log.debug( "Put folder code: " + f.getCode() ) ;
    							}
    						} 						
    					}
    				}
    			} // end while
    		}
    		finally {
    			//
    			// Make sure we recover cursor resources...
    			if( cursor != null )
    				cursor.dispose() ;
    		}
    		
    		if( log.isTraceEnabled() ) exitTrace( "OnyxData2Pdo.Factory.buildOntologicalVariablesCollection()" ) ;
    	}
    	
    }

}
