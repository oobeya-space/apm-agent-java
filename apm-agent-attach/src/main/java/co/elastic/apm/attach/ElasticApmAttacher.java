/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2020 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package co.elastic.apm.attach;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Attaches the Elastic Apm agent to the current or a remote JVM
 */
public class ElasticApmAttacher {

    /**
     * This key is very short on purpose.
     * The longer the agent argument ({@code -javaagent:<path>=<args>}), the greater the chance that the max length of the agent argument is reached.
     * Because of a bug in the {@linkplain ByteBuddyAgent.AttachmentProvider.ForEmulatedAttachment emulated attachment},
     * this can even lead to segfaults.
     */
    private static final String TEMP_PROPERTIES_FILE_KEY = "c";

    /**
     * Attaches the Elastic Apm agent to the current JVM.
     * <p>
     * This method may only be invoked once.
     * </p>
     * <p>
     * Tries to load {@code elasticapm.properties} from the classpath, if exists.
     * </p>
     *
     * @throws IllegalStateException if there was a problem while attaching the agent to this VM
     */
    public static void attach() {
        attach(loadProperties("elasticapm.properties"));
    }

    /**
     * Attaches the Elastic Apm agent to the current JVM.
     * <p>
     * This method may only be invoked once.
     * </p>
     *
     * @param propertiesLocation the location within the classpath which contains the agent configuration properties file
     * @throws IllegalStateException if there was a problem while attaching the agent to this VM
     * @since 1.11.0
     */
    public static void attach(String propertiesLocation) {
        attach(loadProperties(propertiesLocation));
    }

    private static Map<String, String> loadProperties(String propertiesLocation) {
        Map<String, String> propertyMap = new HashMap<>();
        final Properties props = new Properties();
        try (InputStream resourceStream = ElasticApmAttacher.class.getClassLoader().getResourceAsStream(propertiesLocation)) {
            if (resourceStream != null) {
                props.load(resourceStream);
                for (String propertyName : props.stringPropertyNames()) {
                    propertyMap.put(propertyName, props.getProperty(propertyName));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return propertyMap;
    }

    /**
     * Attaches the Elastic Apm agent to the current JVM.
     * <p>
     * This method may only be invoked once.
     * </p>
     *
     * @param configuration the agent configuration
     * @throws IllegalStateException if there was a problem while attaching the agent to this VM
     */
    public static void attach(Map<String, String> configuration) {
        // optimization, this is checked in AgentMain#init again
        if (Boolean.getBoolean("ElasticApm.attached")) {
            return;
        }
        attach(ByteBuddyAgent.ProcessProvider.ForCurrentVm.INSTANCE.resolve(), configuration);
    }

    static File createTempProperties(Map<String, String> configuration) {
        File tempFile = null;
        if (!configuration.isEmpty()) {
            Properties properties = new Properties();
            properties.putAll(configuration);

            // when an external configuration file is used, we have to load it last to give it higher priority
            String externalConfig = configuration.get("config_file");
            if (null != externalConfig) {
                try (FileInputStream stream = new FileInputStream(externalConfig)) {
                    properties.load(stream);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            try {
                tempFile = File.createTempFile("elstcapm", ".tmp");
                try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                    properties.store(outputStream, null);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return tempFile;
    }

    /**
     * Attaches the agent to a remote JVM
     *
     * @param pid           the PID of the JVM the agent should be attached on
     * @param configuration the agent configuration
     */
    public static void attach(String pid, Map<String, String> configuration) {
        attach(pid, configuration, AgentJarFileHolder.INSTANCE.agentJarFile);
    }

    /**
     * Attaches the agent to a remote JVM
     *
     * @param pid           the PID of the JVM the agent should be attached on
     * @param configuration the agent configuration
     * @param agentJarFile
     */
    public static void attach(String pid, Map<String, String> configuration, File agentJarFile) {
        File tempFile = createTempProperties(configuration);
        String agentArgs = tempFile == null ? null : TEMP_PROPERTIES_FILE_KEY + "=" + tempFile.getAbsolutePath();

        ByteBuddyAgent.attach(agentJarFile, pid, agentArgs, ElasticAttachmentProvider.get());
        if (tempFile != null) {
            if (!tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    /**
     * Attaches the agent to a remote JVM
     *
     * @param pid       the PID of the JVM the agent should be attached on
     * @param agentArgs the agent arguments
     * @deprecated use {@link #attach(String, Map)}
     */
    @Deprecated
    public static void attach(String pid, String agentArgs) {
        ByteBuddyAgent.attach(AgentJarFileHolder.INSTANCE.agentJarFile, pid, agentArgs, ElasticAttachmentProvider.get());
    }

    public static File getBundledAgentJarFile() {
        return AgentJarFileHolder.INSTANCE.agentJarFile;
    }

    private enum AgentJarFileHolder {
        INSTANCE;

        // initializes lazily and ensures it's only loaded once
        final File agentJarFile = getAgentJarFile();

        /**
         * {@see co.elastic.apm.agent.util.IOUtils#exportResourceToTemp(java.lang.String, java.lang.String, java.lang.String)} who share the same code.
         */
        private static File getAgentJarFile() {
            try (InputStream agentJar = ElasticApmAttacher.class.getResourceAsStream("/elastic-apm-agent.jar")) {
                if (agentJar == null) {
                    return null;
                }
                String hash = md5Hash(ElasticApmAttacher.class.getResourceAsStream("/elastic-apm-agent.jar"));

                // we have to include current user name as multiple copies of the same agent could be attached
                // to multiple JVMs, each running under a different user. Also, we have to make it path-friendly.
                String user = md5Hash(System.getProperty("user.name"));

                File tempAgentJar = new File(System.getProperty("java.io.tmpdir"), String.format("elastic-apm-agent-%s-%s.jar", user, hash));
                if (!tempAgentJar.exists()) {
                    try (FileOutputStream out = new FileOutputStream(tempAgentJar)) {
                        FileChannel channel = out.getChannel();
                        // If multiple JVM start on same compute, they can write in same file
                        // and this file will be corrupted.
                        try (FileLock ignored = channel.lock()) {
                            if (tempAgentJar.length() == 0) {
                                byte[] buffer = new byte[1024];
                                for (int length; (length = agentJar.read(buffer)) != -1; ) {
                                    out.write(buffer, 0, length);
                                }
                            }
                        }
                    }
                } else if (!md5Hash(new FileInputStream(tempAgentJar)).equals(hash)) {
                    throw new IllegalStateException("Invalid MD5 checksum of " + tempAgentJar + ". Please delete this file.");
                }
                return tempAgentJar;
            } catch (NoSuchAlgorithmException | IOException e) {
                throw new IllegalStateException(e);
            }
        }

    }

    static String md5Hash(InputStream resourceAsStream) throws IOException, NoSuchAlgorithmException {
        try (InputStream agentJar = resourceAsStream) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[1024];
            DigestInputStream dis = new DigestInputStream(agentJar, md);
            while (dis.read(buffer) != -1) {
            }
            return String.format("%032x", new BigInteger(1, md.digest()));
        }
    }

    static String md5Hash(String s) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(s.getBytes());
        return String.format("%032x", new BigInteger(1, md.digest()));
    }
}
