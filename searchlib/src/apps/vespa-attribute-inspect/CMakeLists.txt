# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_executable(searchlib_vespa-attribute-inspect_app
    SOURCES
    vespa-attribute-inspect.cpp
    OUTPUT_NAME vespa-attribute-inspect
    INSTALL bin
    DEPENDS
    vespa_searchlib
)
