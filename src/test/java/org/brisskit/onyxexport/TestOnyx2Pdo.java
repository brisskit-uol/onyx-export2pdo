package org.brisskit.onyxexport;

import org.brisskit.onyxexport.OnyxData2Pdo;
import junit.framework.TestCase;

public class TestOnyx2Pdo extends TestCase {

	protected void setUp() throws Exception { 
		super.setUp();
	}

	protected void tearDown() throws Exception {
		super.tearDown();
	}
	
	public void testGenerateHash() throws Exception {	
		String code = OnyxData2Pdo.generateHash( "Primary_PCI_year5_cat.Unknown" ) ;
		System.out.println( "Primary_PCI_year5_cat.Unknown: " + code ) ;
		assertTrue( code.equals( "6dad9504e5daed0756aebed34335e456") ) ;
	}

}
