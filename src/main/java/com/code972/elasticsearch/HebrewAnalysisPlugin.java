package com.code972.elasticsearch;

import com.code972.elasticsearch.plugins.index.analysis.*;
import com.code972.elasticsearch.plugins.rest.action.RestHebrewAnalyzerCheckWordAction;
import com.code972.hebmorph.DictionaryLoader;
import com.code972.hebmorph.datastructures.DictHebMorph;
import com.code972.hebmorph.hspell.HSpellDictionaryLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.index.analysis.AnalyzerProvider;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static java.util.Collections.*;

/**
 * The Hebrew analysis plugin entry point, locating and loading the dictionary and configuring
 * the tokenizer, token filters and analyzers
 */
public final class HebrewAnalysisPlugin extends Plugin implements ActionPlugin, AnalysisPlugin {

    private final Logger log = LogManager.getLogger(this.getClass());

    private final static String commercialDictionaryLoaderClass = "com.code972.hebmorph.dictionary.impl.HebMorphDictionaryLoader";

    private static DictHebMorph dict;
    public static DictHebMorph getDictionary() {
        return dict;
    }

    /**
     * Attempts to load a dictionary from paths specified in elasticsearch.yml.
     * If hebrew.dict.path is defined, try loading that first.
     *
     * @param settings
     */
    public HebrewAnalysisPlugin(final Settings settings) {
        super();

        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            // unprivileged code such as scripts do not have SpecialPermission
            sm.checkPermission(new SpecialPermission());
        }

        // Figure out which DictionaryLoader class to use for loading the dictionary
        DictionaryLoader dictLoader = (DictionaryLoader) AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
            try {
                final Class clz;
                if ((clz = Class.forName(commercialDictionaryLoaderClass)) != null) {
                    log.info("Dictionary loader available ({})", clz.getSimpleName());
                    try {
                        Constructor ctor = Class.forName(commercialDictionaryLoaderClass).getConstructor();
                        return  (DictionaryLoader) ctor.newInstance();
                    } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
                        log.error("Unable to load the HebMorph dictionary", e);
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // If external dictionary loaders are not present, we default to the one provided with OSS HebMorph
            }
            return null;
        });

        if (dictLoader == null) {
            log.info("Defaulting to HSpell dictionary loader");
            dictLoader = new HSpellDictionaryLoader();
        }

        // If path was specified in settings, try that path first
        final String pathFromSettings = settings.get("hebrew.dict.path");
        if (pathFromSettings != null && !pathFromSettings.isEmpty()) {
            final DictHebMorph tmp = AccessController.doPrivileged(new LoadDictAction(pathFromSettings, dictLoader));
            log.info("Trying to load {} dictionary from path {}", dictLoader.dictionaryLoaderName(), pathFromSettings);
            if (tmp != null) {
                dict = tmp;
                log.info("Dictionary '{}' loaded successfully from path {}", dictLoader.dictionaryLoaderName(), pathFromSettings);
                return;
            }
        }

        for (final String path : dictLoader.dictionaryPossiblePaths()) {
            final DictHebMorph tmp = AccessController.doPrivileged(new LoadDictAction(path, dictLoader));
            log.info("Trying to load {} from path {}", dictLoader.dictionaryLoaderName(), path);
            if (tmp != null) {
                dict = tmp;
                log.info("Dictionary '{}' loaded successfully from path {}", dictLoader.dictionaryLoaderName(), path);
                return;
            }
        }

        throw new IllegalArgumentException("Could not load any dictionary. Aborting!");
        // TODO log "tried paths"
    }

    private class LoadDictAction implements PrivilegedAction<DictHebMorph> {

        private final String path;
        private final DictionaryLoader loader;

        public LoadDictAction(final String path, DictionaryLoader dictLoader) {
            this.path = path;
            this.loader = dictLoader;
        }

        @Override
        public DictHebMorph run() {
            final File file = new File(path);
            if (file.exists()) {
                try {
                    return loader.loadDictionaryFromPath(path);
                } catch (IOException e) {
                    log.error(e);
                }
            }
            return null;
        }
    }

    @Override
    public List<RestHandler> getRestHandlers(Settings settings, RestController restController, ClusterSettings clusterSettings, IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter, IndexNameExpressionResolver indexNameExpressionResolver, Supplier<DiscoveryNodes> nodesInCluster) {
        return singletonList(new RestHebrewAnalyzerCheckWordAction(settings, restController));
    }

    @Override
    public Map<String, AnalysisModule.AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        final Map<String, AnalysisModule.AnalysisProvider<TokenFilterFactory>> extra = new HashMap<>();
        extra.put("hebrew_lemmatizer", (indexSettings, env, name, settings) -> new HebrewLemmatizerTokenFilterFactory(indexSettings, env, name, settings, dict));
        extra.put("niqqud", NiqqudFilterTokenFilterFactory::new);
        extra.put("add_suffix", AddSuffixTokenFilterFactory::new);
        return unmodifiableMap(extra);
    }

    @Override
    public Map<String, AnalysisModule.AnalysisProvider<TokenizerFactory>> getTokenizers() {
        return singletonMap("hebrew", (indexSettings, env, name, settings) -> new HebrewTokenizerFactory(indexSettings, env, name, settings, dict));
    }

    @Override
    public Map<String, AnalysisModule.AnalysisProvider<AnalyzerProvider<? extends Analyzer>>> getAnalyzers() {
        final Map<String, AnalysisModule.AnalysisProvider<AnalyzerProvider<? extends Analyzer>>> extra = new HashMap<>();
        extra.put("hebrew", (indexSettings, env, name, settings) -> new HebrewIndexingAnalyzerProvider(indexSettings, env, name, settings, dict));
        extra.put("hebrew_query", (indexSettings, env, name, settings) -> new HebrewQueryAnalyzerProvider(indexSettings, env, name, settings, dict));
        extra.put("hebrew_query_light", (indexSettings, env, name, settings) -> new HebrewQueryLightAnalyzerProvider(indexSettings, env, name, settings, dict));
        extra.put("hebrew_exact", (indexSettings, env, name, settings) -> new HebrewExactAnalyzerProvider(indexSettings, env, name, settings, dict));
        return unmodifiableMap(extra);
    }
}
