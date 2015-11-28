package org.bimserver.ifc.step.serializer;

/******************************************************************************
 * Copyright (C) 2009-2015  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Hex;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.models.store.IfcHeader;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.PluginManager;
import org.bimserver.plugins.schema.EntityDefinition;
import org.bimserver.plugins.schema.SchemaDefinition;
import org.bimserver.plugins.serializers.ObjectProvider;
import org.bimserver.plugins.serializers.OidConvertingSerializer;
import org.bimserver.plugins.serializers.ProjectInfo;
import org.bimserver.plugins.serializers.SerializerException;
import org.bimserver.plugins.serializers.SerializerInputstream;
import org.bimserver.plugins.serializers.StreamingReader;
import org.bimserver.plugins.serializers.StreamingSerializer;
import org.bimserver.shared.HashMapVirtualObject;
import org.bimserver.shared.HashMapWrappedVirtualObject;
import org.bimserver.shared.MinimalVirtualObject;
import org.bimserver.utils.StringUtils;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

public abstract class IfcStepStreamingSerializer implements StreamingSerializer, StreamingReader, OidConvertingSerializer {
	private static final byte[] NEW_LINE = "\n".getBytes(Charsets.UTF_8);
	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(IfcStepStreamingSerializer.class);
	private static final boolean useIso8859_1 = false;
	private static final EcorePackage ECORE_PACKAGE_INSTANCE = EcorePackage.eINSTANCE;
	private static final String NULL = "NULL";
	private static final String OPEN_CLOSE_PAREN = "()";
	private static final String ASTERISK = "*";
	private static final String PAREN_CLOSE_SEMICOLON = ");";
	private static final String DOT_0 = ".0";
	private static final String DASH = "#";
	private static final String IFC_LOGICAL = "IfcLogical";
	private static final String IFC_BOOLEAN = "IfcBoolean";
	private static final String DOT = ".";
	private static final String COMMA = ",";
	private static final String OPEN_PAREN = "(";
	private static final String CLOSE_PAREN = ")";
	private static final String BOOLEAN_UNDEFINED = ".U.";
	private static final String SINGLE_QUOTE = "'";
	private static final String BOOLEAN_FALSE = ".F.";
	private static final String BOOLEAN_TRUE = ".T.";
	private static final String DOLLAR = "$";
	private static final String WRAPPED_VALUE = "wrappedValue";
	
	private String headerSchema;
	private long writeCounter;
	private ObjectProvider objectProvider;
	private ProjectInfo projectInfo;
	
	private Map<Long, Integer> oidToEid = new HashMap<>();
	private int oidCounter = 1;

	protected static enum Mode {
		HEADER, BODY, FOOTER, FINISHED
	}

	private Mode mode = Mode.HEADER;
	private IfcHeader ifcHeader;
	private PackageMetaData packageMetaData;
	private PrintWriter printWriter;
	
	@Override
	public boolean write(OutputStream outputStream) throws SerializerException, BimserverDatabaseException {
		this.printWriter = new PrintWriter(new OutputStreamWriter(outputStream, Charsets.UTF_8));
		boolean result = false;
		try {
			result = processMode();
		} catch (IOException e) {
			throw new SerializerException(e);
		}
		// TODO This could be slow
		this.printWriter.flush();
		return result;
	}
	
	public Map<Long, Integer> getOidToEid() {
		return oidToEid;
	}
	
	public Mode getMode() {
		return mode;
	}
	
	public void setMode(Mode mode) {
		this.mode = mode;
	}
	
	@Override
	public InputStream getInputStream() {
		return new SerializerInputstream(this);
	}
	
	public IfcStepStreamingSerializer(PluginConfiguration pluginConfiguration) {
	}

	protected void setHeaderSchema(String headerSchema) {
		this.headerSchema = headerSchema;
	}

	@Override
	public void init(ObjectProvider objectProvider, ProjectInfo projectInfo, IfcHeader ifcHeader, PluginManager pluginManager, PackageMetaData packageMetaData) throws SerializerException {
		this.objectProvider = objectProvider;
		this.projectInfo = projectInfo;
		this.ifcHeader = ifcHeader;
		this.packageMetaData = packageMetaData;
	}
	
	public void writeToOutputStream(OutputStream outputStream) throws SerializerException, BimserverDatabaseException {
		try {
			this.printWriter = new PrintWriter(new OutputStreamWriter(outputStream, Charsets.UTF_8));
			while (mode != Mode.FINISHED) {
				processMode();			
			}
		} catch (IOException e) {
			throw new SerializerException(e);
		}
	}

	private boolean processMode() throws IOException, BimserverDatabaseException, SerializerException {
		if (getMode() == Mode.HEADER) {
			writeHeader();
			setMode(Mode.BODY);
		} else if (getMode() == Mode.BODY) {
			HashMapVirtualObject next = objectProvider.next();
			if (next != null) {
				write(next);
				writeCounter++;
			} else {
				setMode(Mode.FOOTER);
			}
		} else if (getMode() == Mode.FOOTER) {
			writeFooter();
			this.printWriter.flush();
			setMode(Mode.FINISHED);
		} else if (getMode() == Mode.FINISHED) {
			return false;
		}
		return true;
	}

	private void writeFooter() throws IOException {
		println("ENDSEC;");
		println("END-ISO-10303-21;");
	}

	private void writeHeader() throws IOException {
		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		println("ISO-10303-21;");
		println("HEADER;");
		if (ifcHeader == null) {
			Date date = new Date();
			println("FILE_DESCRIPTION ((''), '2;1');");
			println("FILE_NAME ('', '" + dateFormatter.format(date) + "', (''), (''), '', 'BIMserver', '');");
			println("FILE_SCHEMA (('" + headerSchema + "'));");
		} else {
			print("FILE_DESCRIPTION ((");
			print(StringUtils.concat(ifcHeader.getDescription(), "'", ", "));
			println("), '" + ifcHeader.getImplementationLevel() + "');");
			println("FILE_NAME ('" + ifcHeader.getFilename() + "', '" + dateFormatter.format(ifcHeader.getTimeStamp()) + "', (" + StringUtils.concat(ifcHeader.getAuthor(), "'", ", ") + "), (" + StringUtils.concat(ifcHeader.getOrganization(), "'", ", ") + "), '" + ifcHeader.getPreProcessorVersion() + "', '" + ifcHeader.getOriginatingSystem() + "', '"	+ ifcHeader.getAuthorization() + "');");

			//	println("FILE_SCHEMA (('" + ifcHeader.getIfcSchemaVersion() + "'));");
			println("FILE_SCHEMA (('" + headerSchema + "'));");
		}
		println("ENDSEC;");
		println("DATA;");
		// println("//This program comes with ABSOLUTELY NO WARRANTY.");
		// println("//This is free software, and you are welcome to redistribute it under certain conditions. See www.bimserver.org <http://www.bimserver.org>");
	}

	private void println(String line) throws IOException {
		printWriter.write(line);
		printWriter.write("\n");
		
//		byte[] bytes = line.getBytes(Charsets.UTF_8);
//		outputStream.write(bytes, 0, bytes.length);
//		outputStream.write(NEW_LINE, 0, NEW_LINE.length);
	}

	private void print(String text) throws IOException {
		printWriter.write(text);
//		byte[] bytes = text.getBytes(Charsets.UTF_8);
//		outputStream.write(bytes, 0, bytes.length);
	}
	
	private void writePrimitive(Object val) throws SerializerException, IOException {
		if (val.getClass().getSimpleName().equals("Tristate")) {
			if (val.toString().equals("TRUE")) {
				print(BOOLEAN_TRUE);
			} else if (val.toString().equals("FALSE")) {
				print(BOOLEAN_FALSE);
			} else if (val.toString().equals("UNDEFINED")) {
				print(BOOLEAN_UNDEFINED);
			}
		} else if (val instanceof Double) {
			if (((Double)val).isInfinite() || (((Double)val).isNaN())) {
				LOGGER.info("Serializing infinite or NaN double as 0.0");
				print("0.0");
			} else {
				String string = val.toString();
				if (string.endsWith(DOT_0)) {
					print(string.substring(0, string.length() - 1));
				} else {
					print(string);
				}
			}
		} else if (val instanceof Boolean) {
			Boolean bool = (Boolean)val;
			if (bool) {
				print(BOOLEAN_TRUE);
			} else {
				print(BOOLEAN_FALSE);
			}
		} else if (val instanceof String) {
			print(SINGLE_QUOTE);
			String stringVal = (String)val;
			for (int i=0; i<stringVal.length(); i++) {
				char c = stringVal.charAt(i);
				if (c == '\'') {
					print("\'\'");
				} else if (c == '\\') {
					print("\\\\");
				} else if (c >= 32 && c <= 126) {
					// ISO 8859-1
					print("" + c);
				} else if (c < 255) {
					//  ISO 10646 and ISO 8859-1 are the same < 255 , using ISO_8859_1
					print("\\X\\" + new String(Hex.encodeHex(Charsets.ISO_8859_1.encode(CharBuffer.wrap(new char[]{(char) c})).array())).toUpperCase());
				} else {
					if (useIso8859_1) {
						// ISO 8859-1 with -128 offset
						ByteBuffer encode = Charsets.ISO_8859_1.encode(new String(new char[]{(char) (c - 128)}));
						print("\\S\\" + (char)encode.get());
					} else {
						// The following code has not been tested (2012-04-25)
						// Use UCS-2 or UCS-4
						
						// TODO when multiple sequential characters should be encoded in UCS-2 or UCS-4, we don't really need to add all those \X0\ \X2\ and \X4\ chars
						if (Character.isLowSurrogate(c)) {
							throw new SerializerException("Unexpected low surrogate range char");
						} else if (Character.isHighSurrogate(c)) {
							// We need UCS-4, this is probably never happening
							if (i + 1 < stringVal.length()) {
								char low = stringVal.charAt(i + 1);
								if (!Character.isLowSurrogate(low)) {
									throw new SerializerException("High surrogate char should be followed by char in low surrogate range");
								}
								try {
									print("\\X4\\" + new String(Hex.encodeHex(Charset.forName("UTF-32").encode(new String(new char[]{c, low})).array())).toUpperCase() + "\\X0\\");
								} catch (UnsupportedCharsetException e) {
									throw new SerializerException(e);
								}
								i++;
							} else {
								throw new SerializerException("High surrogate char should be followed by char in low surrogate range, but end of string reached");
							}
						} else {
							// UCS-2 will do
							print("\\X2\\" + new String(Hex.encodeHex(Charsets.UTF_16BE.encode(CharBuffer.wrap(new char[]{c})).array())).toUpperCase() + "\\X0\\");
						}
					}
				}
			}
			print(SINGLE_QUOTE);
		} else if (val instanceof Enumerator) {
			print("." + val + ".");
		} else {
			print(val == null ? "$" : val.toString());
		}
	}

	private void write(HashMapVirtualObject object) throws SerializerException, IOException {
//		throw new SerializerException("test");
		EClass eClass = object.eClass();
		if (eClass.getEAnnotation("hidden") != null) {
			return;
		}
		print(DASH);
		int convertedKey = getExpressId(object);
		if (convertedKey == -1) {
			throw new SerializerException("Going to serialize an object with id -1 (" + object.eClass().getName() + ")");
		}
		print(String.valueOf(convertedKey));
		print("= ");
		String upperCase = packageMetaData.getUpperCase(eClass);
		if (upperCase == null) {
			throw new SerializerException("Type not found: " + eClass.getName());
		}
		print(upperCase);
		print(OPEN_PAREN);
		boolean isFirst = true;
		
		if (object.eClass().getName().toUpperCase().equals("IFCBOOLEANCLIPPINGRESULT")) {
			System.out.println();
		}
		EntityDefinition entityBN = getSchemaDefinition().getEntityBN(object.eClass().getName());
		for (EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
			if (feature.getEAnnotation("hidden") == null && (entityBN != null && (!entityBN.isDerived(feature.getName()) || entityBN.isDerivedOverride(feature.getName())))) {
				EClassifier type = feature.getEType();
				if (type instanceof EEnum) {
					if (!isFirst) {
						print(COMMA);
					}
					writeEnum(object, feature);
					isFirst = false;
				} else if (type instanceof EClass) {
					EReference eReference = (EReference)feature;
					if (!packageMetaData.isInverse(eReference)) {
						if (!isFirst) {
							print(COMMA);
						}
						writeEClass(object, feature);
						isFirst = false;
					}
				} else if (type instanceof EDataType) {
					if (!isFirst) {
						print(COMMA);
					}
					writeEDataType(object, entityBN, feature);
					isFirst = false;
				}
			}
		}
		println(PAREN_CLOSE_SEMICOLON);
	}

	private int getExpressId(HashMapVirtualObject object) {
		return getExpressId(object.getOid());
	}

	private int getExpressId(long oid) {
		Integer eid = oidToEid.get(oid);
		if (eid == null ) {
			eid = oidCounter++;
			oidToEid.put(oid, eid);
		}
		return eid;
	}

	private void writeEDataType(HashMapVirtualObject object, EntityDefinition entityBN, EStructuralFeature feature) throws SerializerException, IOException {
		if (entityBN != null && entityBN.isDerived(feature.getName())) {
			print(ASTERISK);
		} else if (feature.isMany()) {
			writeList(object, feature);
		} else {
			writeObject(object, feature);
		}
	}

	private void writeEClass(HashMapVirtualObject object, EStructuralFeature feature) throws SerializerException, IOException {
		Object referencedObject = object.eGet(feature);
		if (referencedObject instanceof HashMapVirtualObject && ((HashMapVirtualObject)referencedObject).eClass().getEAnnotation("wrapped") != null) {
			writeWrappedValue(object, feature, ((EObject)referencedObject).eClass());
		} else {
			if (referencedObject instanceof Long) {
				if (object.useFeatureForSerialization(feature)) {
					print(DASH);
					print(String.valueOf(getExpressId((Long) referencedObject)));
				} else {
					print(DOLLAR);
				}
			} else {
				EntityDefinition entityBN = getSchemaDefinition().getEntityBN(object.eClass().getName());
				if (entityBN != null && entityBN.isDerived(feature.getName())) {
					print(ASTERISK);
				} else if (feature.isMany()) {
					writeList(object, feature);
				} else {
					writeObject(object, feature);
				}
			}
		}
	}

	private void writeObject(HashMapVirtualObject object, EStructuralFeature feature) throws SerializerException, IOException {
		Object ref = object.eGet(feature);
		if (ref == null || (feature.isUnsettable() && !object.eIsSet(feature))) {
			EClassifier type = feature.getEType();
			if (type instanceof EClass) {
				EStructuralFeature structuralFeature = ((EClass) type).getEStructuralFeature(WRAPPED_VALUE);
				if (structuralFeature != null) {
					String name = structuralFeature.getEType().getName();
					if (name.equals(IFC_BOOLEAN) || name.equals(IFC_LOGICAL) || structuralFeature.getEType() == EcorePackage.eINSTANCE.getEBoolean()) {
						print(BOOLEAN_UNDEFINED);
					} else {
						print(DOLLAR);
					}
				} else {
					print(DOLLAR);
				}
			} else {
				if (type == EcorePackage.eINSTANCE.getEBoolean()) {
					print(BOOLEAN_UNDEFINED);
				} else if (feature.isMany()) {
					print("()");
				} else {
					print(DOLLAR);
				}
			}
		} else {
			if (ref instanceof HashMapWrappedVirtualObject) {
				writeEmbedded((HashMapWrappedVirtualObject) ref);
			} else if (feature.getEType() == ECORE_PACKAGE_INSTANCE.getEDouble()) {
				EStructuralFeature asStringFeature = object.eClass().getEStructuralFeature(feature.getName() + "AsString");
				String asString = (String) object.eGet(asStringFeature);
				writeDoubleValue((Double)ref, asString, feature);
			} else {
				writePrimitive(ref);
			}
		}
	}

	private void writeDoubleValue(double value, String asString, EStructuralFeature feature) throws SerializerException, IOException {
		if (asString != null) {
			print((String)asString);
			return;
		}
		writePrimitive(value);
	}

	private void writeEmbedded(HashMapWrappedVirtualObject eObject) throws SerializerException, IOException {
		EClass class1 = eObject.eClass();
		print(packageMetaData.getUpperCase(class1));
		print(OPEN_PAREN);
		EStructuralFeature structuralFeature = class1.getEStructuralFeature(WRAPPED_VALUE);
		if (structuralFeature != null) {
			Object realVal = eObject.eGet(structuralFeature);
			if (structuralFeature.getEType() == ECORE_PACKAGE_INSTANCE.getEDouble()) {
				EStructuralFeature asStringFeature = eObject.eClass().getEStructuralFeature(structuralFeature.getName() + "AsString");
				String asString = (String) eObject.eGet(asStringFeature);
				writeDoubleValue((Double)realVal, asString, structuralFeature);
			} else {
				writePrimitive(realVal);
			}
		}
		print(CLOSE_PAREN);
	}

	private void writeList(MinimalVirtualObject object, EStructuralFeature feature) throws SerializerException, IOException {
		List<?> list = (List<?>) object.eGet(feature);
		if (list == null) {
			if (feature.isUnsettable()) {
				print(DOLLAR);
			} else {
				print(OPEN_CLOSE_PAREN);
			}
			return;
		}
		List<?> doubleStingList = null;
		if (feature.getEType() == EcorePackage.eINSTANCE.getEDouble()) {
			EStructuralFeature doubleStringFeature = feature.getEContainingClass().getEStructuralFeature(feature.getName() + "AsString");
			if (doubleStringFeature == null) {
				throw new SerializerException("Field " + feature.getName() + "AsString" + " not found");
			}
			doubleStingList = (List<?>) object.eGet(doubleStringFeature);
		}
		if (list.isEmpty() || !object.useFeatureForSerialization(feature)) {
			if (!feature.isUnsettable()) {
				print(OPEN_CLOSE_PAREN);
			} else {
				print("$");
			}
		} else {
			print(OPEN_PAREN);
			boolean first = true;
			int index = 0;
			for (Object listObject : list) {
				if (!first) {
					print(COMMA);
				}
				if (listObject instanceof Long) {
					print(DASH);
					print(String.valueOf(getExpressId((Long)listObject)));
				} else {
					if (listObject == null) {
						print(DOLLAR);
					} else {
						if (listObject instanceof HashMapWrappedVirtualObject && feature.getEType().getEAnnotation("wrapped") != null) {
							HashMapWrappedVirtualObject eObject = (HashMapWrappedVirtualObject) listObject;
							Object realVal = eObject.eGet(eObject.eClass().getEStructuralFeature("wrappedValue"));
							if (realVal instanceof Double) {
								Object stringVal = eObject.eGet(eObject.eClass().getEStructuralFeature("wrappedValueAsString"));
								if (stringVal != null) {
									print((String) stringVal);
								} else {
									writePrimitive(realVal);
								}
							} else {
								writePrimitive(realVal);
							}
						} else if (listObject instanceof HashMapWrappedVirtualObject) {
							HashMapWrappedVirtualObject eObject = (HashMapWrappedVirtualObject) listObject;
							EClass class1 = eObject.eClass();
							EStructuralFeature structuralFeature = class1.getEStructuralFeature(WRAPPED_VALUE);
							if (structuralFeature != null) {
								Object realVal = eObject.eGet(structuralFeature);
								print(packageMetaData.getUpperCase(class1));
								print(OPEN_PAREN);
								if (realVal instanceof Double) {
									EStructuralFeature asStringFeature = eObject.eClass().getEStructuralFeature(structuralFeature.getName() + "AsString");
									String asString = (String) eObject.eGet(asStringFeature);
									writeDoubleValue((Double)realVal, asString, structuralFeature);
								} else {
									writePrimitive(realVal);
								}
								print(CLOSE_PAREN);
							} else {
								if (feature.getEAnnotation("twodimensionalarray") != null) {
									writeList(eObject, eObject.eClass().getEStructuralFeature("List"));
								} else {
//									LOGGER.info("Unfollowable reference found from " + object + "(" + object.getOid() + ")." + feature.getName() + " to " + eObject + "(" + eObject.getOid() + ")");
								}
							}
						} else {
							if (doubleStingList != null) {
								if (index < doubleStingList.size()) {
									String val = (String)doubleStingList.get(index);
									if (val == null) {
										writePrimitive(listObject);
									} else {
										print(val);
									}
								} else {
									writePrimitive(listObject);
								}
							} else {
								writePrimitive(listObject);
							}
						}
					}
				}
				first = false;
				index++;
			}
			print(CLOSE_PAREN);
		}
	}

	private void writeWrappedValue(HashMapVirtualObject object, EStructuralFeature feature, EClass ec) throws SerializerException, IOException {
		Object get = object.eGet(feature);
		boolean isWrapped = ec.getEAnnotation("wrapped") != null;
		EStructuralFeature structuralFeature = ec.getEStructuralFeature(WRAPPED_VALUE);
		if (get instanceof EObject) {
			boolean isDefinedWrapped = feature.getEType().getEAnnotation("wrapped") != null;
			HashMapVirtualObject betweenObject = (HashMapVirtualObject) get;
			if (betweenObject != null) {
				if (isWrapped && isDefinedWrapped) {
					Object val = betweenObject.eGet(structuralFeature);
					String name = structuralFeature.getEType().getName();
					if ((name.equals(IFC_BOOLEAN) || name.equals(IFC_LOGICAL)) && val == null) {
						print(BOOLEAN_UNDEFINED);
					} else if (structuralFeature.getEType() == ECORE_PACKAGE_INSTANCE.getEDouble()) {
						EStructuralFeature asStringFeature = betweenObject.eClass().getEStructuralFeature(feature.getName() + "AsString");
						String asString = (String) betweenObject.eGet(asStringFeature);
						writeDoubleValue((Double)val, asString, feature);
					} else {
						writePrimitive(val);
					}
				} else {
//					writeEmbedded(betweenObject);
				}
			}
		} else if (get instanceof EList<?>) {
			EList<?> list = (EList<?>) get;
			if (list.isEmpty()) {
				if (!feature.isUnsettable()) {
					print(OPEN_CLOSE_PAREN);
				} else {
					print("$");
				}
			} else {
				print(OPEN_PAREN);
				boolean first = true;
				for (Object o : list) {
					if (!first) {
						print(COMMA);
					}
					HashMapVirtualObject object2 = (HashMapVirtualObject) o;
					Object val = object2.eGet(structuralFeature);
					if (structuralFeature.getEType() == ECORE_PACKAGE_INSTANCE.getEDouble()) {
						EStructuralFeature asStringFeature = object2.eClass().getEStructuralFeature(feature.getName() + "AsString");
						String asString = (String) object2.eGet(asStringFeature);
						writeDoubleValue((Double)val, asString, structuralFeature);
					} else {
						writePrimitive(val);
					}
					first = false;
				}
				print(CLOSE_PAREN);
			}
		} else {
			if (get == null) {
				EClassifier type = structuralFeature.getEType();
				if (type.getName().equals("IfcBoolean") || type.getName().equals("IfcLogical") || type == ECORE_PACKAGE_INSTANCE.getEBoolean()) {
					print(BOOLEAN_UNDEFINED);
				} else {
					EntityDefinition entityBN = getSchemaDefinition().getEntityBN(object.eClass().getName());
					if (entityBN != null && entityBN.isDerived(feature.getName())) {
						print(ASTERISK);
					} else {
						print(DOLLAR);
					}
				}
			}
		}
	}

	private SchemaDefinition getSchemaDefinition() {
		return packageMetaData.getSchemaDefinition();
	}

	private void writeEnum(HashMapVirtualObject object, EStructuralFeature feature) throws SerializerException, IOException {
		Object val = object.eGet(feature);
		if (feature.getEType().getName().equals("Tristate")) {
			writePrimitive( val);
		} else {
			if (val == null) {
				print(DOLLAR);
			} else {
				if (((Enum<?>) val).toString().equals(NULL)) {
					print(DOLLAR);
				} else {
					print(DOT);
					print(val.toString());
					print(DOT);
				}
			}
		}
	}
}