package org.brisskit.onyxexport;

import java.util.Calendar;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xmlbeans.XmlDateTime;
import org.apache.xmlbeans.XmlObject;

import org.brisskit.onyxdata.beans.ValueSetType;
import org.brisskit.onyxdata.beans.VariableValueType;
import org.brisskit.onyxmetadata.stageone.beans.QuestionType;
import org.brisskit.export.metadata.config.beans.DateContextType;

public class Export2PdoUserImpl implements IExport2Pdo {
	
	private static final String[] INTERVENTIONS_THIS_CLINICAL_EPISODE =
	{ 
		"epi_intcabg", "epi_intvalve", "epi_inttavi", "epi_intppci",
		"epi_intopci", "epi_intpace", "epi_inticd", "epi_intlvad", 
		"epi_intthromb", "epi_intablat", "epi_testangio" 
	} ;
	
	private static Log log = LogFactory.getLog( Export2PdoUserImpl.class ) ;
	
	private OnyxData2Pdo od2p ;

	@Override
	public Calendar generateStartDate( ValueSetType vst, VariableValueType value ) {
		Calendar calendar = null ;
		XmlObject xo = null ;
		String valueTableName = vst.getValueTable() ;

		if( valueTableName.equals( "DataSubmissionQuestionnaire" ) ) {
			if( isPrincipleSymptoms( value.getVariable() ) ) {
				calendar = constructPrincipleSymptomsDate() ;
				if( calendar != null ) {
					return calendar ;
				}
				else {
					log.warn( "Failed to construct principle symptoms onset observation start date!" ) ;
				}			
			}
			else if( isInterventionThisClinicalEpisode( value.getVariable() ) ) {
				xo = od2p.getValueAsXmlObject( valueTableName, value.getVariable() ) ;
				calendar = od2p.getCorrectedDateTimeValue( xo ) ;
				return calendar ;
			}
		}	
		return null ;
	}

	@Override
	public Calendar generateEndDate( ValueSetType vst, VariableValueType value ) {
		// BEWARE!
		// We are setting the end date to the same as the start date for the only two situations
		// catered for here ( principle symptoms and interventions this clinical episode).
		// This may not be suitable for all situations.
		return generateStartDate( vst, value );
	}
	
	/**
	 * @param variableName
	 * @return true if the variable concerns principle symptoms, false otherwise
	 */
	private boolean isPrincipleSymptoms( String variableName ) {
		if( variableName.indexOf( '.') > 0 ) {
			String[] parts = variableName.split( "\\." ); 
			if( parts[0].equals( "epi_symptoms" ) ) {
				return true ;				
			}
		}
		return false ;		
	}
	
	/**
	 * @param variableName
	 * @return true if the variable concerns interventions this clinical episode,
	 *         false otherwise.
	 */
	private boolean isInterventionThisClinicalEpisode( String variableName ) {
		if( variableName.startsWith( "epi_" ) ) {
			for( int i=0; i<INTERVENTIONS_THIS_CLINICAL_EPISODE.length; i++ ) {
				if( variableName.equals( INTERVENTIONS_THIS_CLINICAL_EPISODE[i] ) ) {
					return true ;
				}
			}
		}
		return false ;
	}
	
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
	private  Calendar constructPrincipleSymptomsDate() {
		Calendar psd = null ;

		//
		// This is a painfully inefficient way of doing it.
		// Optimization can wait until later!
		String sEpiSymponset, sEpiSymponsetHour, sEpiSymponsetMin ;
		String sEpiSymponsetYear, sEpiSymponsetMonth ;

		sEpiSymponset = od2p.getValue( "DataSubmissionQuestionnaire", "epi_symponset" ) ;
		if( sEpiSymponset != null ) {
			sEpiSymponset = od2p.getCorrectedDateTimeValueFromString( sEpiSymponset ) ;
			sEpiSymponsetHour = od2p.getValue( "DataSubmissionQuestionnaire", "epi_symponset_hour" ) ;
			sEpiSymponsetMin = od2p.getValue( "DataSubmissionQuestionnaire", "epi_symponset_min" ) ;

			XmlDateTime dt = XmlDateTime.Factory.newInstance() ;
			dt.setStringValue( sEpiSymponset ) ;
			psd = dt.getCalendarValue() ;
			if( sEpiSymponsetHour != null && sEpiSymponsetMin != null) {
				psd.set( Calendar.HOUR_OF_DAY, Integer.valueOf( sEpiSymponsetHour ) ) ;
				psd.set( Calendar.MINUTE, Integer.valueOf( sEpiSymponsetMin ) ) ;
			}
			log.debug( "Principle symptoms date. Route1. " + psd.toString() ) ;
			return psd ;
		}
		sEpiSymponsetYear = od2p.getValue( "DataSubmissionQuestionnaire", "epi_symponset_year" ) ;
		if( sEpiSymponsetYear != null ) {
			sEpiSymponsetMonth = od2p.getValue( "DataSubmissionQuestionnaire", "epi_symponset_month" ) ;
			psd = Calendar.getInstance() ;
			psd.setTimeInMillis( 0 ) ;
			psd.set( Integer.valueOf( sEpiSymponsetYear ), Integer.valueOf( sEpiSymponsetMonth ), 1, 0, 0) ;
			log.debug( "Principle symptoms date. Route2. " + psd.toString() ) ;
			return psd ;
		}
		log.debug( "Principle symptoms date. Route3. " + psd ) ;
		return psd ;	
	}

	@Override
	public void setOnyxData2Pdo(OnyxData2Pdo od2p) {
		this.od2p = od2p ;
	}

	@Override
	public boolean isNonGeneratedEnumeration(String variableName) {	
		return isInterventionThisClinicalEpisode( variableName ) ;
	}

}
