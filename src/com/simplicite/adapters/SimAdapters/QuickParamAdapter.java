package com.simplicite.adapters.SimAdapters;

import java.util.*;
import com.simplicite.util.*;
import com.simplicite.util.integration.*;

import com.simplicite.util.tools.*;
import org.apache.commons.lang3.StringUtils;
import com.simplicite.util.exceptions.PlatformException;
import com.simplicite.util.exceptions.SaveException;
import com.simplicite.util.exceptions.ValidateException;

/**
 * QuickParamAdapter
 * ====================
 * 
 * Quickly creates Objects, ObjectField, Attributes, Template from CSV file
 * 
 * Example : 
 * 
 * ```
 * Module,Object,Trigram,Attribute,Type,Length
 * TestQuickparam,Télédéclaration,tld,Numéro,CHAR,10
 * ```
 */
public class QuickParamAdapter extends CSVLineBasedAdapter {
	private static final long serialVersionUID = 1L;
	private static final int IDX_MODULE = 0;
	private static final int IDX_OBJECT = 1;
	private static final int IDX_TRIGRAM = 2;
	private static final int IDX_ATTRIBUTE = 3;
	private static final int IDX_ATTRIBUTE_HELP = 4;
	private static final int IDX_TYPE = 5;
	private static final int IDX_LENGTH = 6;
	
	private HashMap<String, String> typeMap; // type label => type code (int)
	private HashMap<String, ObjectDB> objectsMap; // ObjectName => ObjectDB
	private HashMap<String, ArrayList<String>> objectFieldsMap; // ObjectName => list of fields (=> build template)
	private HashMap<String, String> fieldOrdersMap; // ObjectName => maxOrder
	
	private ObjectDB objectInternal, field, objectField, translateObject, translateField;
	private String lang;
	
	private static class ProcessQuickParamException extends Exception{
		public ProcessQuickParamException(String message){
			super(message);
		}
	}
	
	@Override
	public String preProcess(){
		setSeparator(',');
		lang = "FRA";
		
		buildTypeMap();
		objectsMap = new HashMap<String, ObjectDB>();
		objectFieldsMap = new HashMap<String, ArrayList<String>>();
		fieldOrdersMap = new HashMap<String, String>();
		
		objectInternal = getGrant().getBatchObject("ObjectInternal");
		translateObject = getGrant().getBatchObject("TranslateObject");
		field = getGrant().getBatchObject("Field");
		translateField = getGrant().getBatchObject("TranslateField");
		objectField = getGrant().getBatchObject("ObjectFieldSystem");
		
		return null;
	}
	
	@Override
	public String processValues(long lineNumber, String[] values) throws PlatformException, InterruptedException{
		if(lineNumber==1){
			appendLog("=== Ignoring header line (#"+lineNumber+") : "+Arrays.toString(values));
		}
		else{
			try{
				appendLog("=== Processing line #"+lineNumber+" : "+Arrays.toString(values));
				processWithExceptions(lineNumber, values);
				appendLog("");
			}
			catch(ProcessQuickParamException e){
				appendError("=== Error at line #"+lineNumber+" : "+Arrays.toString(values));
				appendError(e);
				setStatus(SystemXML.SUPERVISOR_STATUS_IMPORT_ERROR);
			}
		}
		return null;
	}
	
	@Override
    public void postProcess(){
        appendLog("End Process with status "+getStatus());
        try{
	        for (Map.Entry<String, ArrayList<String>> entry : objectFieldsMap.entrySet()) {
				String objectLogicalName = entry.getKey();
				ArrayList<String> fields = entry.getValue();
				// TODO **should** exist. implement verification
				ObjectDB obj = objectsMap.get(objectLogicalName);
				
				// TODO clean method...
				EditTemplate editor = new EditTemplate(getGrant(), objectLogicalName, "ObjectInternal");
				editor.createTemplate(getGrant().simpleQuery("select row_id from m_template where tpl_name='Base'"), obj.getModuleId());
			}
        }
        catch(ValidateException|SaveException e){
    		appendError("=== Error creating template : "+e.getMessage());
			appendError(e);
			setStatus(SystemXML.SUPERVISOR_STATUS_IMPORT_ERROR);
        }
    }
	
	public void processWithExceptions(long lineNumber, String[] values) throws ProcessQuickParamException{
		if(values.length < IDX_LENGTH+1)
			throw new ProcessQuickParamException("Line #"+lineNumber+" missing values. Check CSV format.");
			
		// CSV info
		String moduleName = values[IDX_MODULE];
		String objectTranslation = values[IDX_OBJECT];
		String objectPrefix = values[IDX_TRIGRAM].toLowerCase();
		String attributeTranslation = values[IDX_ATTRIBUTE];
		String attibuteSimpleHelp = values[IDX_ATTRIBUTE_HELP];
		String attributeTypeLabel = values[IDX_TYPE];
		String attributeLength = values[IDX_LENGTH];
		
		// Module info
		String moduleId = getModuleId(moduleName);
		String modulePrefix = getPrefixModule(moduleId);
		appendLog("Using module "+moduleName+" with prefix "+modulePrefix);
		
		// Object info
		String objectLogicalName = getObjectName(objectTranslation, modulePrefix);
		
		// Load or create object
		ObjectDB obj = null;
		if(objectsMap.containsKey(objectLogicalName)){
			obj = objectsMap.get(objectLogicalName);
		}
		else if(objectExists(objectLogicalName)){
			obj = loadObject(objectLogicalName, objectPrefix, moduleName);
			objectsMap.put(objectLogicalName, obj);
		}
		else{
			obj = createAndLoadObject(objectLogicalName, objectPrefix, objectTranslation, moduleName);
			objectsMap.put(objectLogicalName, obj);
		}
		appendLog("Using object : "+ objectLogicalName +" | "+objectTranslation);
		
		// Attribute info
		String attributeLogicalName = getAttributeName(attributeTranslation, objectPrefix, modulePrefix);
		String attributePhysicalName = SyntaxTool.caseConvert(attributeLogicalName, SyntaxTool.CAMEL, SyntaxTool.SNAKE);
		String type = typeMap.get(attributeTypeLabel);
		
		// TODO verifications
		// TODO field size and precision only if available
		// TODO use EditTemplate static methods
		synchronized(field){
			field.resetValues(true);
			field.setFieldValue("fld_name", attributeLogicalName);
			field.setFieldValue("fld_dbname", attributePhysicalName);
			field.setFieldValue("fld_type", type);
			if(type.equals(""+ObjectField.TYPE_ENUM) || type.equals(""+ObjectField.TYPE_ENUM_MULTI)){
				String fieldListName = SyntaxTool.caseConvert(attributeLogicalName, SyntaxTool.CAMEL, SyntaxTool.UPPER);
				try{
					createList(fieldListName, moduleId, getGrant());
				}
				catch(ValidateException|SaveException e){
					throw new ProcessQuickParamException(e.getMessage());
				}
				field.setFieldValue("lov_name", fieldListName);
			}
			field.setFieldValue("fld_size", attributeLength);
			field.setFieldValue("mdl_name", moduleName);
			field.completeForeignKeys();
			field.create();
		}
		synchronized(translateField){
			translateField.resetValues(true);
			translateField.setFieldValue("fld_name", attributeLogicalName);
			translateField.setFieldValue("tsl_value", attributeTranslation);
			translateField.setFieldValue("mdl_name", moduleName);
			translateField.setFieldValue("tsl_lang", lang);
			translateField.setFieldValue("tsl_simplehelp", attibuteSimpleHelp);
			//translateField.setFieldValue("tsl_short_value", );
			//translateField.setFieldValue("tsl_placeholder", );
			//translateField.setFieldValue("tsl_listhelp", );
			//translateField.setFieldValue("tsl_tooltip", );
			translateField.completeForeignKeys();
			translateField.create();
		}
		synchronized(objectField){
			objectField.resetValues(true);
			objectField.setFieldValue("obf_order", getObjectFieldOrder(objectLogicalName));
			objectField.setFieldValue("fld_name", attributeLogicalName);
			objectField.setFieldValue("obo_name", objectLogicalName);
			objectField.setFieldValue("mdl_name", moduleName);
			objectField.completeForeignKeys();
			objectField.create();
		}
		addForTemplate(objectLogicalName, attributeLogicalName);
		
		appendLog("Created attribute : "+attributeLogicalName+" | "+attributePhysicalName+" | "+attributeTranslation+" | "+attributeTypeLabel+" | "+type);
	}
	
	private void addForTemplate(String objectLogicalName, String attributeLogicalName){
		if(!objectFieldsMap.containsKey(objectLogicalName))
			objectFieldsMap.put(objectLogicalName, new ArrayList<String>());
		
		objectFieldsMap.get(objectLogicalName).add(attributeLogicalName);
	}
	
	private String getObjectFieldOrder(String objectLogicalName){
		String order = 
			fieldOrdersMap.containsKey(objectLogicalName)
			? "" + (Integer.parseInt(fieldOrdersMap.get(objectLogicalName))+10)
			: "10";
		fieldOrdersMap.put(objectLogicalName, order);
		return order;
	}
	
	private boolean objectExists(String objectLogicalName){
		return !Tool.isEmpty(ObjectCore.getObjectId(objectLogicalName));
	}
	
	private ObjectDB loadObject(String objectLogicalName, String objectPrefix, String moduleName) throws ProcessQuickParamException{
		ObjectDB obj = getGrant().getBatchObject(objectLogicalName);
			
		// ensure we have the good moduleName & objectPrefix values + object OK
		if(obj == null)
			throw new ProcessQuickParamException("Configuration corruption error : object exists but not loadable.");
		else if(!objectPrefix.equals(SyntaxTool.getObjectPrefix(obj.getId()).toLowerCase()))
			throw new ProcessQuickParamException("Object prefix does not correspond to object found in DB for : "+objectLogicalName);
		else if(!moduleName.equals(obj.getModuleName()))
			throw new ProcessQuickParamException("Object module does not correspond to object found in DB for : "+objectLogicalName);
			
		return obj;
	}
	
	private ObjectDB createAndLoadObject(String objectLogicalName, String objectPrefix, String objectTranslation, String moduleName) throws ProcessQuickParamException{
		String objectPhysicalName = SyntaxTool.caseConvert(objectLogicalName, SyntaxTool.PASCAL, SyntaxTool.SNAKE);
		String msgCreate;
		synchronized(objectInternal){
			objectInternal.resetValues(true);
			objectInternal.setFieldValue("obo_name", objectLogicalName);
			objectInternal.setFieldValue("obo_dbtable", objectPhysicalName);
			objectInternal.setFieldValue("obo_prefix", objectPrefix);
			objectInternal.setFieldValue("mdl_name", moduleName);
			objectInternal.completeForeignKeys();
			msgCreate = objectInternal.create();
		}
		if(!Tool.isEmpty(msgCreate))
			throw new ProcessQuickParamException("Error creating "+objectLogicalName+" object : "+msgCreate);
		
		synchronized(translateObject){
			translateObject.resetValues(true);
			translateObject.setFieldValue("obo_name", objectLogicalName);
			translateObject.setFieldValue("tsl_lang", lang);
			translateObject.setFieldValue("tsl_value", objectTranslation);
			translateObject.setFieldValue("mdl_name", moduleName);
			translateObject.completeForeignKeys();
			translateObject.create();
		}
		// TODO verif table created & tsl created
		
		ObjectDB obj = getGrant().getBatchObject(objectLogicalName);
		if(obj == null)
			throw new ProcessQuickParamException("Configuration corruption error : object created but not loadable.");
		return obj;
	}
	
	private String getModuleId(String moduleName) throws ProcessQuickParamException{
		String idModule = ModuleDB.getModuleId(moduleName);
		if(Tool.isEmpty(idModule)) 
			throw new ProcessQuickParamException("Module not found : "+moduleName);
		return idModule;
	}
	
	private String getPrefixModule(String moduleId) throws ProcessQuickParamException{
		String prefixModule = SyntaxTool.getModulePrefix(moduleId);
		if(Tool.isEmpty(prefixModule)) 
			throw new ProcessQuickParamException("Empty module prefix for module #"+moduleId);
		return prefixModule;
	}
	
	private String getObjectName(String tsl, String prefixModule){
		tsl = StringUtils.stripAccents(tsl);
		return SyntaxTool.prefixate(SyntaxTool.PASCAL, SyntaxTool.forceCase(tsl, SyntaxTool.PASCAL, true), prefixModule);
	}
	
	private String getAttributeName(String tsl, String objectPrefix, String modulePrefix){
		return SyntaxTool.prefixate(
			SyntaxTool.CAMEL,
			SyntaxTool.forceCase(StringUtils.stripAccents(tsl), SyntaxTool.CAMEL, true),
			new String[] { modulePrefix, objectPrefix }
		);
	}
	
	private void buildTypeMap(){
		typeMap = new HashMap<String, String>();
		String type;
		for(int i=0; i<ObjectField.TYPE_BIGDECIMAL+1; i++){
			type = ObjectField.getTypeLabel(i,0,0).split("\\(")[0];
			appendLog(type);
			typeMap.put(type, ""+i);
		}
	}
	
	// TODO from EditTemplate, make it static
	public static String createList(String name, String moduleId, Grant m_grant) throws ValidateException, SaveException
	{
		synchronized(m_grant)
		{
			name = name.toUpperCase();

			ObjectDB lov = m_grant.getTmpObject("FieldList");
			lov.resetFilters();
			lov.setFieldFilter("lov_name", name.trim());
			List<String[]> list = lov.search();
			if (list.size()==0)
			{
				lov.resetValues(true, ObjectField.DEFAULT_ROW_ID);
				lov.setFieldValue("lov_name", name.trim());
				lov.setFieldValue("row_module_id", moduleId);
				new BusinessObjectTool(lov).validateAndSave();

				// Generate default codes
				ObjectDB cod = m_grant.getTmpObject("FieldListCode");
				for (int i=0; i<3; i++)
				{
					String code = "ABC".substring(i,i+1);
					cod.resetValues(true, ObjectField.DEFAULT_ROW_ID);
					cod.setFieldValue("lov_list_id", lov.getRowId());
					cod.setFieldValue("lov_code", code.trim());
					cod.setFieldValue("lov_order_by", (i+1)*10);
					cod.setFieldValue("lov_label", "code "+code);
					cod.setFieldValue("row_module_id", moduleId);
					new BusinessObjectTool(cod).validateAndSave();
					// Values are created in FieldListCode.postCreate
				}
				return lov.getRowId();
			}
			return list.get(0)[0];
		}
	}
	
	// TODO from EditTemplate, make it static
	public void createTemplate(String baseId, String moduleId, String m_object, Grant m_grant) throws ValidateException, SaveException
	{
		synchronized(m_grant)
		{
			ObjectDB t = m_grant.getTmpObject("Template");
			t.resetFilters();
			if (t.select(baseId))
			{
				// BaseNx
				String base = t.getFieldValue("tpl_name");
				String ui = t.getFieldValue("tpl_ui");

				// Template
				t.setFieldFilter("tpl_name", m_object);
				List<String[]> list = t.search();
				if (list.size()>0)
					t.setValues(list.get(0), true);
				else
					t.resetValues(true, ObjectField.DEFAULT_ROW_ID);
				t.setFieldValue("tpl_name", m_object);
				t.setFieldValue("tpl_ui", ui);
				t.setFieldValue("tpl_image", "");
				t.setFieldValue("row_module_id", moduleId);
				if (t.hasChanged(false))
					new BusinessObjectTool(t).validateAndSave();

				// Object template
				ObjectDB o = m_grant.getTmpObject("ObjectInternal");
				o.resetFilters();
				if (o.select(ObjectDB.getObjectId(m_object)))
				{
					o.setFieldValue("obo_template_id", t.getRowId());
					if (o.hasChanged(false))
						new BusinessObjectTool(o).validateAndSave();
				}

				// Generate areas
				/*int n = 1;
				try { n = Integer.parseInt(base.substring(4,5)); } catch(Exception e) {}
				for (int i=0; i<n; i++)
					updateFieldArea(i+1, null, null, true, null, moduleId);*/
			}
		}
	}
}
