package org.brisskit.onyxexport;

import java.util.Calendar;

import org.brisskit.onyxdata.beans.ValueSetType;
import org.brisskit.onyxdata.beans.VariableValueType;

public interface IExport2Pdo {
	
	public void setOnyxData2Pdo( OnyxData2Pdo od2p ) ;

	public Calendar generateStartDate( ValueSetType vst, VariableValueType value ) ;
	
	public Calendar generateEndDate( ValueSetType vst, VariableValueType value ) ;
	
	public boolean isNonGeneratedEnumeration( String variableName ) ;
	
}
