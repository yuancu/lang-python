/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.python;

import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;

/**
 *
 * Registers Python as a plugin.
 *
 * PythonModulePlugin is a plugin for OpenSearch that adds support for Python as a scripting language.
 * This class extends the {@link Plugin} class and implements the {@link ScriptPlugin} interface.
 *
 * <p>
 * This plugin allows users to write and execute scripts in Python within OpenSearch.
 * </p>
 *
 */
public class PythonModulePlugin extends Plugin implements ScriptPlugin {
    /**
     * Constructor for PythonModulePlugin.
     */
    public PythonModulePlugin() {
        // Implement the relevant Plugin Interfaces here
    }
}
