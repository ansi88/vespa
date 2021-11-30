// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document;

import com.yahoo.compress.CompressionType;
import com.yahoo.config.subscription.ConfigSubscriber;
import com.yahoo.document.config.DocumentmanagerConfig;
import com.yahoo.document.annotation.AnnotationReferenceDataType;
import com.yahoo.document.annotation.AnnotationType;
import java.util.logging.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Configures the Vespa document manager from a config id.
 *
 * @author Einar M R Rosenvinge
 */
public class DocumentTypeManagerConfigurer implements ConfigSubscriber.SingleSubscriber<DocumentmanagerConfig>{

    private final static Logger log = Logger.getLogger(DocumentTypeManagerConfigurer.class.getName());

    private final DocumentTypeManager managerToConfigure;

    public DocumentTypeManagerConfigurer(DocumentTypeManager manager) {
        this.managerToConfigure = manager;
    }

    /** Deprecated and will go away on Vespa 8 */
    @Deprecated
    public static CompressionType toCompressorType(DocumentmanagerConfig.Datatype.Structtype.Compresstype.Enum value) {
        switch (value) {
            case NONE: return CompressionType.NONE;
            case LZ4: return CompressionType.LZ4;
            case UNCOMPRESSABLE: return CompressionType.INCOMPRESSIBLE;
        }
        throw new IllegalArgumentException("Compression type " + value + " is not supported");
    }
    /**
     * <p>Makes the DocumentTypeManager subscribe on its config.</p>
     *
     * <p>Proper Vespa setups will use a config id which looks up the document manager config
     * at the document server, but it is also possible to read config from a file containing
     * a document manager configuration by using
     * <code>file:path-to-document-manager.cfg</code>.</p>
     *
     * @param configId the config ID to use
     */
    public static ConfigSubscriber configure(DocumentTypeManager manager, String configId) {
        return new DocumentTypeManagerConfigurer(manager).configure(configId);
    }

    public ConfigSubscriber configure(String configId) {
        ConfigSubscriber subscriber = new ConfigSubscriber();
        subscriber.subscribe(this, DocumentmanagerConfig.class, configId);
        return subscriber;
    }

    /** One-shot configuration; should be called on a newly constructed manager */
    static void configureNewManager(DocumentmanagerConfig config, DocumentTypeManager manager) {
        if (config == null) {
            return;
        }
        new Apply(config, manager);
    }

    private static class Apply {
        public Apply(DocumentmanagerConfig config, DocumentTypeManager manager) {
            this.manager = manager;
            this.usev8geopositions = (config == null) ? false : config.usev8geopositions();
            if (config != null) {
                apply(config);
            }
        }

        private void apply(DocumentmanagerConfig config) {
            setupAnnotationTypesWithoutPayloads(config);
            setupAnnotationRefTypes(config);
            setupDatatypesWithRetry(config);
            addStructInheritance(config);
            addAnnotationTypePayloads(config);
            addAnnotationTypeInheritance(config);
            manager.replaceTemporaryTypes();
        }

        private void setupDatatypesWithRetry(DocumentmanagerConfig config) {
            var tmp = new ArrayList<DocumentmanagerConfig.Datatype>(config.datatype());
            log.log(Level.FINE, "Configuring document manager with " + tmp.size() + " data types.");
            while (! tmp.isEmpty()) {
                int oldSz = tmp.size();
                var failed = new ArrayList<DocumentmanagerConfig.Datatype>();
                for (DocumentmanagerConfig.Datatype thisDataType : tmp) {
                    int id = thisDataType.id();
                    try {
                        registerTypeIdMapping(thisDataType, id);
                    } catch (IllegalArgumentException e) {
                        failed.add(thisDataType);
                    }
                }
                tmp = failed;
                if (tmp.size() == oldSz) {
                    throw new IllegalArgumentException("No progress registering datatypes");
                }
            }
        }

        private void registerTypeIdMapping(DocumentmanagerConfig.Datatype thisDataType, int id) {
            for (var o : thisDataType.arraytype()) {
                registerArrayType(id, o);
            }
            for (var o : thisDataType.maptype()) {
                registerMapType(id, o);
            }
            for (var o : thisDataType.weightedsettype()) {
                registerWeightedSetType(id, o);
            }
            for (var o : thisDataType.structtype()) {
                registerStructType(id, o);
            }
            for (var o : thisDataType.documenttype()) {
                registerDocumentType(o);
            }
            for (var o : thisDataType.referencetype()) {
                registerReferenceType(id, o);
            }
        }

        private void registerArrayType(int id, DocumentmanagerConfig.Datatype.Arraytype array) {
            DataType nestedType = manager.getDataType(array.datatype(), "");
            ArrayDataType type = new ArrayDataType(nestedType, id);
            manager.register(type);
        }

        private void registerMapType(int id, DocumentmanagerConfig.Datatype.Maptype map) {
            DataType keyType = manager.getDataType(map.keytype(), "");
            DataType valType = manager.getDataType(map.valtype(), "");
            MapDataType type = new MapDataType(keyType, valType, id);
            manager.register(type);
        }

        private void registerWeightedSetType(int id, DocumentmanagerConfig.Datatype.Weightedsettype wset) {
            DataType nestedType = manager.getDataType(wset.datatype(), "");
            WeightedSetDataType type = new WeightedSetDataType(
                                                               nestedType, wset.createifnonexistant(), wset.removeifzero(), id);
            manager.register(type);
        }

        private void registerDocumentType(DocumentmanagerConfig.Datatype.Documenttype doc) {
            StructDataType header = (StructDataType) manager.getDataType(doc.headerstruct(), "");
            var importedFields = doc.importedfield().stream()
                .map(f -> f.name())
                .collect(Collectors.toUnmodifiableSet());
            DocumentType type = new DocumentType(doc.name(), header, importedFields);
            for (var parent : doc.inherits()) {
                DataTypeName name = new DataTypeName(parent.name());
                DocumentType parentType = manager.getDocumentType(name);
                if (parentType == null) {
                    throw new IllegalArgumentException("Could not find document type '" + name + "'.");
                }
                type.inherit(parentType);
            }
            Map<String, Collection<String>> fieldSets = new HashMap<>(doc.fieldsets().size());
            for (Map.Entry<String, DocumentmanagerConfig.Datatype.Documenttype.Fieldsets> entry: doc.fieldsets().entrySet()) {
                fieldSets.put(entry.getKey(), entry.getValue().fields());
            }
            type.addFieldSets(fieldSets);
            manager.register(type);
        }

        private void registerStructType(int id, DocumentmanagerConfig.Datatype.Structtype struct) {
            StructDataType type = new StructDataType(id, struct.name());

            for (var field : struct.field()) {
                DataType fieldType = (field.datatype() == id)
                    ? manager.getDataTypeAndReturnTemporary(field.datatype(), field.detailedtype())
                    : manager.getDataType(field.datatype(), field.detailedtype());

                if (field.id().size() == 1) {
                    type.addField(new Field(field.name(), field.id().get(0).id(), fieldType));
                } else {
                    type.addField(new Field(field.name(), fieldType));
                }
            }
            /*
            if (type.equals(PositionDataType.INSTANCE)) {
                if (this.usev8geopositions) {
                     // do something special here
                }
            }
            */
            manager.register(type);
        }

        @SuppressWarnings("deprecation")
        private void registerReferenceType(int id, DocumentmanagerConfig.Datatype.Referencetype refType) {
            ReferenceDataType referenceType;
            if (manager.hasDataType(refType.target_type_id())) {
                DocumentType targetDocType = (DocumentType)manager.getDataType(refType.target_type_id());
                referenceType = new ReferenceDataType(targetDocType, id);
            } else {
                TemporaryStructuredDataType temporaryTargetType = TemporaryStructuredDataType.createById(refType.target_type_id());
                referenceType = new ReferenceDataType(temporaryTargetType, id);
            }
            // Note: can't combine the above new-statements, as they call different constructors.
            manager.register(referenceType);
        }

        private void setupAnnotationRefTypes(DocumentmanagerConfig config) {
            for (int i = 0; i < config.datatype().size(); i++) {
                DocumentmanagerConfig.Datatype thisDataType = config.datatype(i);
                int id = thisDataType.id();
                for (var annRefType : thisDataType.annotationreftype()) {
                    AnnotationType annotationType = manager.getAnnotationTypeRegistry().getType(annRefType.annotation());
                    if (annotationType == null) {
                        throw new IllegalArgumentException("Found reference to " + annRefType.annotation() + ", which does not exist!");
                    }
                    AnnotationReferenceDataType type = new AnnotationReferenceDataType(annotationType, id);
                    manager.register(type);
                }
            }
        }

        private void setupAnnotationTypesWithoutPayloads(DocumentmanagerConfig config) {
            for (DocumentmanagerConfig.Annotationtype annType : config.annotationtype()) {
                AnnotationType annotationType = new AnnotationType(annType.name(), annType.id());
                manager.getAnnotationTypeRegistry().register(annotationType);
            }
        }

        private void addAnnotationTypePayloads(DocumentmanagerConfig config) {
            for (DocumentmanagerConfig.Annotationtype annType : config.annotationtype()) {
                AnnotationType annotationType = manager.getAnnotationTypeRegistry().getType(annType.id());
                DataType payload = manager.getDataType(annType.datatype(), "");
                if (!payload.equals(DataType.NONE)) {
                    annotationType.setDataType(payload);
                }
            }

        }

        private void addAnnotationTypeInheritance(DocumentmanagerConfig config) {
            for (DocumentmanagerConfig.Annotationtype annType : config.annotationtype()) {
                if (annType.inherits().size() > 0) {
                    AnnotationType inheritedType = manager.getAnnotationTypeRegistry().getType(annType.inherits(0).id());
                    AnnotationType type = manager.getAnnotationTypeRegistry().getType(annType.id());
                    type.inherit(inheritedType);
                }
            }
        }

        private void addStructInheritance(DocumentmanagerConfig config) {
            for (int i = 0; i < config.datatype().size(); i++) {
                DocumentmanagerConfig.Datatype thisDataType = config.datatype(i);
                int id = thisDataType.id();
                for (var struct : thisDataType.structtype()) {
                    StructDataType thisStruct = (StructDataType) manager.getDataType(id, "");

                    for (var parent : struct.inherits()) {
                        StructDataType parentStruct = (StructDataType) manager.getDataType(parent.name());
                        thisStruct.inherit(parentStruct);
                    }
                }
            }
        }

        private final boolean usev8geopositions;
        private final DocumentTypeManager manager;
    }

    public static DocumentTypeManager configureNewManager(DocumentmanagerConfig config) {
        DocumentTypeManager manager = new DocumentTypeManager();
        new Apply(config, manager);
        return manager;
    }

    /**
     * Called by the configuration system to register document types based on documentmanager.cfg.
     *
     * @param config the instance representing config in documentmanager.cfg.
     */
    @Override
    public void configure(DocumentmanagerConfig config) {
        DocumentTypeManager manager = configureNewManager(config);
        int defaultTypeCount = new DocumentTypeManager().getDataTypes().size();
        if (this.managerToConfigure.getDataTypes().size() != defaultTypeCount) {
            log.log(Level.FINE, "Live document config overwritten with new config.");
        }
        managerToConfigure.assign(manager);
    }

}
