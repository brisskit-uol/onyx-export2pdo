package org.brisskit.onyxexport;

import java.util.Calendar;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xmlbeans.XmlDateTime;
import org.apache.xmlbeans.XmlObject;

import org.brisskit.pdo.beans.EidType;
import org.brisskit.pdo.beans.PidType;
import org.brisskit.pdo.beans.EidType.EventId;
import org.brisskit.pdo.beans.PidType.PatientId;
import org.brisskit.pdo.beans.PidType.PatientMapId;

public class OnyxData2PdoWs extends OnyxData2Pdo {

	private static Log log = LogFactory.getLog( OnyxData2PdoWs.class ) ;
	
	protected OnyxData2PdoWs() {}
	
	/**
	 * Builds the Patient Mapping. <p/>
	 * 
	 * <b>Notes</b>:
	 * <p><pre>
	 * 
	 * 3. What about the admin group of dates?
	 *    (Update date, Download date, Import date).
	 *    In effect these are being set to the run date (unless it's a test run).
	 * 4. What about Source, Source System Id and Upload Id?
	 * </pre><p>
	 * 
	 * 
	 */
	protected void buildPatientMapping() {
		if( log.isTraceEnabled() ) enterTrace( "buildPatientMapping()" ) ;
		try {
			PidType pt = getPatientMappingSet().addNewPid() ;

			PatientId pid = pt.addNewPatientId() ;
			pid.setSource( getCurrentPatientSource() ) ;
			pid.setStatus( "Active" ) ;
			pid.setStringValue( getCurrentPatientNum() ) ;
			
			pid.setUpdateDate( adminCalendar ) ;
			pid.setDownloadDate( adminCalendar ) ;
			pid.setImportDate( adminCalendar ) ;
			pid.setSourcesystemCd( this.projectAcronymn ) ;
			//
			// Add a mapping for UHLT s-number if not a test run...
//			if( OnyxData2Pdo.testFlag == false ) {
//				
//				PatientMapId pmapid = pt.addNewPatientMapId() ;
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
			
		}
		finally {
			if( log.isTraceEnabled() ) exitTrace( "buildVisitMapping()" ) ;
		}
		
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentEncounterNum()
	 */
	@Override
	protected String getCurrentEncounterNum() {
		//
		// First, get a suitable value for a date...
		XmlObject xo = getValueAsXmlObject( "Participants", "Admin.Participant.captureStartDate" ) ;
		Calendar cal = getCorrectedDateTimeValue( xo ) ;
		if( OnyxData2Pdo.testFlag == true ) {
			cal = adjustCalendarForTestRun( cal ) ;
		}
		XmlDateTime xdt = XmlDateTime.Factory.newInstance() ;
		xdt.setCalendarValue( cal ) ;
		String eventdate = getText( xdt ) ;
		//
		// Construct an artificial source event identifier...
		StringBuilder b = new StringBuilder() ;
		b.append( getEnrollmentId() )
		 .append( '_' )
		 .append( eventdate ) ;
		return b.toString() ;
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentPatientNum()
	 */
	@Override
	protected String getCurrentPatientNum() {
		return getEnrollmentId() ;
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentEncounterSource()
	 */
	@Override
	protected String getCurrentEncounterSource() {
		return this.projectAcronymn ;
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentPatientSource()
	 */
	@Override
	protected String getCurrentPatientSource() {
		return this.projectAcronymn ;
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentObserverNum()
	 */
	@Override
	protected String getCurrentObserverNum() {
		return "@";
	}

	/* (non-Javadoc)
	 * @see org.brisskit.onyxexport.OnyxData2Pdo#getCurrentObserverSource()
	 */
	@Override
	protected String getCurrentObserverSource() {
		return this.projectAcronymn ;
	}
	
	
}
