/*
 * Copyright 2013-2014, ApiFest project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apifest;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.apifest.api.Mapping;
import com.apifest.api.Mapping.Backend;
import com.apifest.api.MappingAction;
import com.apifest.api.MappingEndpoint;
import com.apifest.api.MappingError;
import com.apifest.api.ResponseFilter;

/**
 * Loads/reloads all mapping configurations.
 *
 * @author Rossitsa Borissova
 */
public final class MappingConfigLoader {

    private static Logger log = LoggerFactory.getLogger(MappingConfigLoader.class);

    protected static URLClassLoader jarClassLoader;

    private MappingConfigLoader() {
    }

    protected static void load() {
        String mappingFileDir = ServerConfig.getMappingsPath();
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(Mapping.class);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            File configPath = new File(mappingFileDir);
            Map<String, MappingConfig> map = getHazelcastConfig();
            if (configPath.isDirectory()) {
                File[] files = configPath.listFiles();
                // load all config files
                for (File mappingFile : files) {
                    if (!mappingFile.isFile() || !mappingFile.getName().endsWith(".xml")) {
                        continue;
                    }
                    MappingConfig config = new MappingConfig();
                    Mapping mappings = (Mapping) unmarshaller.unmarshal(mappingFile);
                    config.setActions(getActionsMap(mappings));
                    if (mappings.getFiltersWrapper() != null) {
                        config.setFilters(getFiltersMap(mappings));
                    }

                    if (mappings.getErrorsWrapper() != null) {
                        config.setErrors(getErrorsMap(mappings));
                    }

                    List<MappingEndpoint> mappingEndpoints = mappings.getEndpointsWrapper().getEndpoints();
                    config.setMappings(getMappingsMap(mappingEndpoints, mappings.getBackend()));

                    // load all actions and filters
                    if (ServerConfig.getCustomJarPath() != null && ServerConfig.getCustomJarPath().length() > 0) {
                        try {
                            loadCustomClasses(config.getActions().values());
                        } catch (MalformedURLException e) {
                            log.error("Cannot load custom jar file", e);
                            throw new IllegalArgumentException(e);
                        }
                    }
                    map.put(mappings.getVersion(), config);
                }
            } else {
                log.error("Cannot load mapping configuration from directory {}", mappingFileDir);
                throw new IllegalArgumentException();
            }
        } catch (JAXBException e) {
            log.error("Cannot load mapping configuration, directory {}", mappingFileDir);
            throw new IllegalArgumentException(e);
        }
    }

    public static Class<?> loadCustomClass(String className) throws MappingException, ClassNotFoundException {
        if (jarClassLoader == null) {
            try {
                createJarClassLoader();
            } catch (MalformedURLException e) {
                throw new MappingException("cannot load custom class", e);
            }
        }
        return jarClassLoader.loadClass(className);
    }

    private static void createJarClassLoader() throws MalformedURLException {
        File file = new File(ServerConfig.getCustomJarPath());
        URL jarfile = file.toURI().toURL();
        jarClassLoader = URLClassLoader.newInstance(new URL[] { jarfile }, MappingConfigLoader.class.getClassLoader());
    }

    private static void loadCustomClasses(Collection<String> actionClasses) throws MalformedURLException {
        if (jarClassLoader == null) {
            createJarClassLoader();
        }
        for (String className : actionClasses) {
            try {
                jarClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                log.error("cannot load custom class {}", className, e);
            }
        }
    }

    public static List<MappingConfig> getConfig() {
        Map<String, MappingConfig> map = HazelcastConfigInstance.instance().getMappingConfigs();
        return new ArrayList<MappingConfig>(map.values());
    }

    private static Map<String, String> getActionsMap(Mapping configs) {
        List<MappingAction> mappingActions = configs.getActionsWrapper().getActions();
        Map<String, String> actions = new HashMap<String, String>();
        for (MappingAction action : mappingActions) {
            actions.put(action.getName(), action.getActionClassName());
        }
        return actions;
    }

    private static Map<String, String> getFiltersMap(Mapping configs) {
        List<ResponseFilter> responseFilters = configs.getFiltersWrapper().getFilters();
        Map<String, String> filters = new HashMap<String, String>();
        for (ResponseFilter filter : responseFilters) {
            filters.put(filter.getName(), filter.getFilterClassName());
        }
        return filters;
    }

    private static Map<String, String> getErrorsMap(Mapping configs) {
        List<MappingError> mappingErrors = configs.getErrorsWrapper().getErrors();
        Map<String, String> errors = new HashMap<String, String>();
        for (MappingError error : mappingErrors) {
            errors.put(error.getStatus(), error.getMessage());
        }
        return errors;
    }

    protected static Map<MappingPattern, MappingEndpoint> getMappingsMap(List<MappingEndpoint> mappingEndpoints, Backend backend) {
        Map<MappingPattern, MappingEndpoint> mappings = new HashMap<MappingPattern, MappingEndpoint>();
        for (MappingEndpoint endpoint : mappingEndpoints) {
            // construct regular expression
            Pattern p = constructPattern(endpoint);
            MappingPattern pattern = new MappingPattern(p, endpoint.getMethod());
            if(endpoint.getBackendHost() == null) {
                endpoint.setBackendHost(backend.getBackendHost());
                endpoint.setBackendPort(backend.getBackendPort());
            }
            mappings.put(pattern, endpoint);
        }
        return mappings;
    }

    protected static Pattern constructPattern(MappingEndpoint endpoint) {
        String path = endpoint.getExternalEndpoint();
        if (endpoint.getExternalEndpoint().contains("{")) {
            String varExpr = "(" + endpoint.getVarExpression() + ")";
            String varName = "{" + endpoint.getVarName() + "}";
            path = path.replace(varName, varExpr);
            return Pattern.compile(path);
        } else {
            // add $ for regular expressions - for no RE path
            return Pattern.compile(path + "$");
        }
    }

    protected static Map<String, MappingConfig> getHazelcastConfig() {
        return HazelcastConfigInstance.instance().getMappingConfigs();
    }

    /**
     * Reloads all mapping configs.
     */
    public static void reloadConfigs() {
        // TODO: Think about how to switch between old and new version of the mapping configs (use version?)
        jarClassLoader = null;
        load();

    }
}
