package org.brisskit.onyxexport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.brisskit.pdo.beans.EidType;
import org.brisskit.pdo.beans.PidType;
import org.brisskit.pdo.beans.EidType.EventId;
import org.brisskit.pdo.beans.EidType.EventMapId;
import org.brisskit.pdo.beans.PidType.PatientId;
import org.brisskit.pdo.beans.PidType.PatientMapId;

public class OnyxData2PdoSql extends OnyxData2Pdo {

	private static Log log = LogFactory.getLog( OnyxData2PdoSql.class ) ;
	
	private int patientNumber ;
	private int encounterNumber ;
	
	protected OnyxData2PdoSql( int patientNumber, int encounterNumber ) {
		this.patientNumber = patientNumber ;
		this.encounterNumber = encounterNumber ;
	}
	
	/**
	 * Builds the Patient Mapping. <p/>
	 * 
	 * <b>Notes</b>:
	 * <p><pre>
	 * 1. Maps the internal i2b2 identifier (patient number) to the BRICCS participant id.
	 * 2. We are setting the patient number here manually, as it were. 
	 * 3. What about the admin group of dates?
	 *    (Update date, Download date, Import date).
	 *    In effect these are being set to the run date (unless it's a test run).
	 * 4. What about Source, Source System Id and Upload Id?
	 * </pre><p>
	 */
	protected void buildPatientMapping() {
		if( log.isTraceEnabled() ) enterTrace( "buildPatientMapping()" ) ;
		try {
			//
			// Prime the patient number...
			nextPatientNum() ;
			
			PidType pt = getPatientMappingSet().addNewPid() ;

			PatientId pid = pt.addNewPatientId() ;
			pid.setSource( getCurrentPatientSource() ) ;
			pid.setStatus( "Active" ) ;
			pid.setStringValue( getCurrentPatientNum() ) ;
			
			pid.setUpdateDate( adminCalendar ) ;
			pid.setDownloadDate( adminCalendar ) ;
			pid.setImportDate( adminCalendar ) ;
			pid.setSourcesystemCd( this.projectAcronymn ) ;
			
			PatientMapId pmapid = pt.addNewPatientMapId() ;
			pmapid.setSource( this.projectAcronymn ) ;
			pmapid.setStatus( "Active" ) ;
			pmapid.setStringValue( getEnrollmentId() ) ;
			
			pmapid.setUpdateDate( adminCalendar ) ;
			pmapid.setDownloadDate( adminCalendar ) ;
			pmapid.setImportDate( adminCalendar ) ;
			pmapid.setSourcesystemCd( this.projectAcronymn ) ;
			
//			//
//			// Add a mapping for UHLT s-number if not a test run...
//			// Added as a result of trac issue 94.
//			// As a result of trac 94 we eliminated the mapping
//			// data from being in the patient_dimension table.
//			// Placing the data within the patient_dimension meant introducing new
//			// optional columns, something we wished to avoid given our present
//			// level of knowledge over what would be required.
//			if( OnyxData2Pdo.testFlag == false ) {
//				
//				pmapid = pt.addNewPatientMapId() ;
//				pmapid.setSource( this.systemAcroymn ) ;
//				pmapid.setStatus( "Active" ) ;
//				pmapid.setStringValue( getEnrollmentId() ) ;
//				
//				pmapid.setUpdateDate( adminCalendar ) ;
//				pmapid.setDownloadDate( adminCalendar ) ;
//				pmapid.setImportDate( adminCalendar ) ;
//				pmapid.setSourcesystemCd( this.projectAcronymn ) ;
//			}
			
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildPatientMapping()" ) ;
		}
	}
	
	
	
    /* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#buildVisitMapping()
	 */
	@Override
	protected void buildVisitMapping() {
		if( log.isTraceEnabled() ) enterTrace( "buildVisitMapping()" ) ;
		try {
			//
			// Prime the event number...
			nextEncounterNum() ;
			
			EidType et = getVisitMappingSet().addNewEid() ;

			EventId eid = et.addNewEventId() ;
			eid.setSource( getCurrentEncounterSource() ) ;
			eid.setStatus( "Active" ) ;
			eid.setStringValue( getCurrentEncounterNum() ) ;
			eid.setPatientIdSource( getCurrentPatientSource() ) ;
			eid.setPatientId( getCurrentPatientNum() ) ;			
			
			eid.setUpdateDate( adminCalendar ) ;
			eid.setDownloadDate( adminCalendar ) ;
			eid.setImportDate( adminCalendar ) ;
			eid.setSourcesystemCd( this.projectAcronymn ) ;
			
			EventMapId emapid = et.addNewEventMapId() ;
			emapid.setSource( this.projectAcronymn ) ;
			emapid.setStatus( "Active" ) ;
			emapid.setStringValue( getEnrollmentId() ) ;
			
			emapid.setUpdateDate( adminCalendar ) ;
			emapid.setDownloadDate( adminCalendar ) ;
			emapid.setImportDate( adminCalendar ) ;
			emapid.setSourcesystemCd( this.projectAcronymn ) ;
			
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildVisitMapping()" ) ;
		}
		
	}

	/**
     * @return The next i2b2 patient number.
     */
    private int nextPatientNum() {
    	return ++patientNumber ;
    }
    
    /**
     * @return The next i2b2 encounter number.
     */
    private int nextEncounterNum() {
    	return ++encounterNumber ;
    }
    
    /**
     * @return The current i2b2 patient number.
     */
    protected String getCurrentPatientNum() {
    	return String.valueOf( patientNumber ) ;
    }
    
    /**
     * @return The current i2b2 encounter number.
     */
    protected String getCurrentEncounterNum() {
    	return String.valueOf( encounterNumber ) ;
    }

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentEncounterSource()
	 */
	@Override
	protected String getCurrentEncounterSource() {
		return HIVE ;
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentPatientSource()
	 */
	@Override
	protected String getCurrentPatientSource() {
		return HIVE ;
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentObserverNum()
	 */
	@Override
	protected String getCurrentObserverNum() {
		return "@" ;
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentObserverSource()
	 */
	@Override
	protected String getCurrentObserverSource() {
		return HIVE;
	}
    
    
	
}
