package com.simplicite.adapters.SimAdapters;

import java.util.*;
import com.simplicite.util.*;
import com.simplicite.util.integration.*;
import com.simplicite.util.exceptions.PlatformException;
import com.simplicite.util.tools.*;
import org.json.JSONObject;

// -----------------------------------------------------------------------------------------------------------
// Note: you should consider using one of com.simplicite.util.integration sub classes instead of SimpleAdapter
// -----------------------------------------------------------------------------------------------------------

/**
 * Adapter QuickListAdapter
 */
public class QuickListAdapter extends CSVLineBasedAdapter {
	private static final long serialVersionUID = 1L;
	private BusinessObjectTool list,item,tsl;
	private Grant g;

	// Good practice : use specific exception class
	private static class QuickListAdapterException extends Exception{
		public QuickListAdapterException(String message){
			super(message);
		}
		
		public QuickListAdapterException(String message, String msg2){
			super(message+" "+msg2);
		}
	}
	
	public String preProcess(){
		g = getGrant();
		// set CSV separator
		setSeparator(','); 
		list = g.getProcessObject("FieldList").getTool();
		item = g.getProcessObject("FieldListCode").getTool();
		tsl = g.getProcessObject("FieldListValue").getTool();

		// to generate a subsequently imported XML, call super.preProcess()
		// doing so will add a starting <simplicite> tag
		return null;
	}
	
	@Override
	public String processValues(long lineNumber, String[] values){	
		if(lineNumber==1){
			appendLog("=== Ignoring header line (#"+lineNumber+") : "+Arrays.toString(values));
		}
		else{
			try{
				// add some logs to the .log file (added in the imports supervisor object)
				appendLog("=== Processing line #"+lineNumber+" : "+Arrays.toString(values));
				processLine(lineNumber, values);
			}
			catch(Exception e){
				// add some logs to the .err file (added in the imports supervisor object)
				appendError("=== Error with line #"+lineNumber+" : "+Arrays.toString(values));
				appendError(e);
	
				// change import status to impact the supervisor object
				setStatus(SystemXML.SUPERVISOR_STATUS_IMPORT_ERROR);
			}
		}

		// returned String gets added to a XML subsequently imported.
		// in this case we do not append anything to XML subsequently imported,
		// as we directly create the objects instead
		return null; 
	}

	public void postProcess(){
		appendLog("End Process with status "+getStatus());
		// to generate a subsequently imported XML, call super.postProcess()
		// doing so will add a closing <simplicite> tag
	}
	
	private final int NB_COLUMNS = 6;
	private final int COL_MODULE = 0;
	private final int COL_LISTCODE = 1;
	private final int COL_ITEMCODE = 2;
	private final int COL_ITEMDESCR = 3;
	private final int COL_LANG = 4;
	private final int COL_TSL = 5;

	public void processLine(long lineNumber, String[] values) throws QuickListAdapterException, PlatformException{
		if(lineNumber==1)
			appendLog("=== Ignoring header line (#"+lineNumber+") : "+Arrays.toString(values));
		else if(values.length!=NB_COLUMNS)
			throw new QuickListAdapterException("DSN_ERR_NB_VALUES");
		else{
			String listId = upsertList(values);
			String itemId = upsertItem(listId, lineNumber, values);
			upsertTsl(itemId, values);
		}
	}
	
	private String upsertList(String[] values) throws PlatformException{
		boolean exists = list.getForUpsert(new JSONObject().put("lov_name", values[COL_LISTCODE]));
		if(!exists){
			list.getObject().setFieldValue("row_module_id", ModuleDB.getModuleId(values[COL_MODULE]));
			list.getObject().setFieldValue("lov_name", values[COL_LISTCODE]);
			list.validateAndSave();
		}
		return list.getObject().getRowId();
	}
	
	private String upsertItem(String listId, long line, String[] values) throws PlatformException{
		boolean exists = item.getForUpsert(new JSONObject().put("lov_list_id", listId).put("lov_code", values[COL_ITEMCODE]));
		if(!exists){
			item.getObject().setFieldValue("lov_list_id", listId);
			item.getObject().setFieldValue("lov_code", values[COL_ITEMCODE]);
			list.getObject().setFieldValue("row_module_id", ModuleDB.getModuleId(values[COL_MODULE]));
		}
		item.getObject().setFieldValue("lov_label", values[COL_ITEMDESCR]);
		item.getObject().setFieldValue("lov_order_by", line);
		item.validateAndSave();
		return item.getObject().getRowId();		
	}
	
	private void upsertTsl(String itemId, String[] values) throws PlatformException{
		boolean exists = tsl.getForUpsert(new JSONObject().put("lov_code_id", itemId).put("lov_lang", values[COL_LANG]));
		if(!exists){
			tsl.getObject().setFieldValue("lov_code_id", itemId);
			tsl.getObject().setFieldValue("lov_lang", values[COL_LANG]);
		}
		tsl.getObject().setFieldValue("lov_value", values[COL_TSL]);
		tsl.validateAndSave();
	}
}
