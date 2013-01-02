/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.indices.analysis;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.lucene.analysis.hunspell.HunspellDictionary;
import org.apache.lucene.util.Version;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.io.*;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Serves as a node level registry for hunspell dictionaries. This services expects all dictionaries to be located under
 * the {@code <path.conf>/hunspell} directory, where each locale has its dedicated sub-directory which holds the dictionary
 * files. For example, the dictionary files for {@code en_US} locale must be placed under {@code <path.conf>/hunspell/en_US}
 * directory.
 *
 * The following settings can be set for each dictionary:
 * <ul>
 *     <li>{@code ignore_case} - If true, dictionary matching will be case insensitive (defaults to {@code false})</li>
 *     <li>{@code strict_affix_parsing} - Determines whether errors while reading a affix rules file will cause exception or simple be ignored (defaults to {@code true})</li>
 * </ul>
 *
 * These settings can either be configured as node level configuration, such as:
 * <br/><br/>
 * <pre><code>
 *     indices.analysis.hunspell.dictionary.en_US.ignore_case: true
 *     indices.analysis.hunspell.dictionary.en_US.strict_affix_parsing: false
 * </code></pre>
 *
 * or, as dedicated configuration per dictionary, placed in a {@code settings.yml} file under the dictionary directory. For
 * example, the following can be the content of the {@code <path.config>/hunspell/en_US/settings.yml} file:
 * <br/><br/>
 * <pre><code>
 *     ignore_case: true
 *     strict_affix_parsing: false
 * </code></pre>
 *
 * @see org.elasticsearch.index.analysis.HunspellTokenFilterFactory
 */
public class HunspellService extends AbstractComponent {

    private final static DictionaryFileFilter DIC_FILE_FILTER = new DictionaryFileFilter();
    private final static AffixFileFilter AFFIX_FILE_FILTER = new AffixFileFilter();

    private final LoadingCache<String, HunspellDictionary> dictionaries;
    private final Map<String, HunspellDictionary> knownDicitionaries;

    private final boolean defaultIgnoreCase;
    private final boolean defaultStrictAffixParsing;
    private final File hunspellDir;

    public HunspellService(final Settings settings, final Environment env) {
        this(settings, env, Collections.<String, HunspellDictionary>emptyMap());
    }

    @Inject
    public HunspellService(final Settings settings, final Environment env, final Map<String, HunspellDictionary> knownDicitionaries) {
        super(settings);
        this.knownDicitionaries = knownDicitionaries;
        this.hunspellDir = resolveHunspellDirectory(settings, env);
        this.defaultIgnoreCase = settings.getAsBoolean("indices.analysis.hunspell.dictionary.ignore_case", false);
        this.defaultStrictAffixParsing = settings.getAsBoolean("indices.analysis.hunspell.dictionary.strict_affix_parsing", false);
        final Version version = Lucene.parseVersion(settings.get("version"), Lucene.ANALYZER_VERSION, logger);
        dictionaries = CacheBuilder.newBuilder().build(new CacheLoader<String, HunspellDictionary>() {
            @Override
            public HunspellDictionary load(String locale) throws Exception {
                HunspellDictionary dictionary = knownDicitionaries.get(locale);
                if (dictionary == null) {
                    dictionary = loadDictionary(locale, settings, env, version);
                }
                return dictionary;
            }
        });
        scanAndLoadDictionaries();
    }

    /**
     * Returns the hunspell dictionary for the given locale.
     *
     * @param locale The name of the locale
     */
    public HunspellDictionary getDictionary(String locale) {
        return dictionaries.getUnchecked(locale);
    }

    private File resolveHunspellDirectory(Settings settings, Environment env) {
        String location = settings.get("indices.analysis.hunspell.dictionary.location", null);
        if (location != null) {
            return new File(location);
        }
        return new File(env.configFile(), "hunspell");
    }

    /**
     * Scans the hunspell directory and loads all found dictionaries
     */
    private void scanAndLoadDictionaries() {
        if (hunspellDir.exists() && hunspellDir.isDirectory()) {
            for (File file : hunspellDir.listFiles()) {
                if (file.isDirectory()) {
                    if (file.list(AFFIX_FILE_FILTER).length > 0) { // just making sure it's indeed a dictionary dir
                        dictionaries.getUnchecked(file.getName());
                    }
                }
            }
        }
    }

    /**
     * Loads the hunspell dictionary for the given local.
     *
     * @param locale The locale of the hunspell dictionary to be loaded.
     * @param nodeSettings The node level settings
     * @param env The node environment (from which the conf path will be resolved)
     * @param version The lucene version
     * @return The loaded Hunspell dictionary
     * @throws Exception when loading fails (due to IO erros or malformed dictionary files)
     */
    private HunspellDictionary loadDictionary(String locale, Settings nodeSettings, Environment env, Version version) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Loading huspell dictionary [{}]...", locale);
        }
        File hunspellConfDir = new File(env.configFile(), "hunspell");
        File dicDir = new File(hunspellConfDir, locale);
        if (!dicDir.exists() || !dicDir.isDirectory()) {
            throw new ElasticSearchException(String.format("Could not find hunspell dictionary [%s]", locale));
        }

        // merging node settings with hunspell dictionary specific settings
        nodeSettings = loadDictionarySettings(dicDir, nodeSettings.getByPrefix("indices.analysis.hunspell.dictionary." + locale + "."));

        boolean ignoreCase = nodeSettings.getAsBoolean("ignore_case", defaultIgnoreCase);
        boolean strictAffixParsing = nodeSettings.getAsBoolean("strict_affix_parsing", defaultStrictAffixParsing);

        File[] affixFiles = dicDir.listFiles(AFFIX_FILE_FILTER);
        if (affixFiles.length != 1) {
            throw new ElasticSearchException(String.format("Missing affix file for hunspell dictionary [%s]", locale));
        }
        InputStream affixStream = null;

        File[] dicFiles = dicDir.listFiles(DIC_FILE_FILTER);
        List<InputStream> dicStreams = new ArrayList<InputStream>(dicFiles.length);
        try {

            for (int i = 0; i < dicFiles.length; i++) {
                dicStreams.add(new FileInputStream(dicFiles[i]));
            }

            affixStream = new FileInputStream(affixFiles[0]);

            return new HunspellDictionary(affixStream, dicStreams, version, ignoreCase, strictAffixParsing);

        } catch (Exception e) {
            logger.error("Could not load hunspell dictionary [{}]", e, locale);
            throw e;
        } finally {
            if (affixStream != null) {
                try {
                    affixStream.close();
                } catch (IOException e) {
                    // nothing much we can do here
                }
            }
            for (InputStream in : dicStreams) {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        // nothing much we can do here
                    }
                }
            }
        }
    }

    /**
     * Each hunspell dictionary directory may contain a {@code settings.yml} which holds dictionary specific settings. Default
     * values for these settings are defined in the given default settings.
     *
     * @param dir The directory of the dictionary
     * @param defaults The default settings for this dictionary
     * @return The resolved settings.
     */
    private static Settings loadDictionarySettings(File dir, Settings defaults) {
        File file = new File(dir, "settings.yml");
        if (file.exists()) {
            try {
                return ImmutableSettings.settingsBuilder().loadFromUrl(file.toURI().toURL()).put(defaults).build();
            } catch (MalformedURLException e) {
                throw new ElasticSearchException(String.format("Could not load hunspell dictionary settings from [%s]", file.getAbsolutePath()), e);
            }
        }

        file = new File(dir, "settings.json");
        if (file.exists()) {
            try {
                return ImmutableSettings.settingsBuilder().loadFromUrl(file.toURI().toURL()).put(defaults).build();
            } catch (MalformedURLException e) {
                throw new ElasticSearchException(String.format("Could not load hunspell dictionary settings from [%s]", file.getAbsolutePath()), e);
            }
        }

        return defaults;
    }

    /**
     * Only accepts {@code *.dic} files
     */
    static class DictionaryFileFilter implements FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".dic");
        }
    }

    /**
     * Only accepts {@code *.aff} files
     */
    static class AffixFileFilter implements FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".aff");
        }
    }

}

