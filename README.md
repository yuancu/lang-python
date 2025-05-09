# Python Language Plugin for OpenSearch

This plugin is created from [template for creating OpenSearch Plugins](https://github.com/opensearch-project/opensearch-plugin-template-java).
Please consult the original template repository for more information on building and running this plugin.

## Build & Install the Plugin
1. Download and install [GraalVM JDK](https://www.graalvm.org/downloads/).
2. Build the plugin using the following command:
   ```bash
   ./gradlew assemble
   ```
   The generated plugin zip should locate at `build/distributions/lang-python-3.0.0.0.zip`
3. Download [opensearch-3.0.0 snapshot](https://artifacts.opensearch.org/snapshots/core/opensearch/3.0.0-SNAPSHOT/opensearch-min-3.0.0-SNAPSHOT-darwin-x64-latest.tar.gz) and unzip it.
4. Set the `OPENSEARCH_JAVA_HOME` environment variable to point to GraalVM JDK's Java home.
5. Change the directory to the unzipped OpenSearch directory.
6. Install the plugin use the following command
    ```bash
    ./bin/opensearch-plugin install file:///path/to/lang-ptyhon/build/distributions/lang-python-3.0.0.0.zip
    ```

## Run OpenSearch with the Plugin
1. Disable java security manager with a policy file

    This is a limitation of the current implementation of the plugin. It is hard to exhaust all the permissions required
    by running Python on GraalVM's truffle API, so we use a permissive policy file to allow all permissions. This
    requirement is to be removed in the future.

   1. Create a file named `all-grant.policy` with the following content:
      ```
      grant {
          permission java.security.AllPermission;
      };
      ```
   2. In `/path/to/opensearch/config/jvm.options`, add the following line:
      ```
      -Djava.security.policy=/path/to/all-grant.policy
      ```
2. Set the `OPENSEARCH_JAVA_HOME` environment variable to point to GraalVM JDK's Java home.
3. Start OpenSearch with the plugin installed:
   ```bash
   ./bin/opensearch
   ```
   The following instructions assumes that OpenSearch is running on `localhost:9200`.
4. Verify that the `lang-python` plugin is installed:
    ```bash
    curl --location 'localhost:9200/_cat/plugins'
    ```
    You should see an entry for `lang-python` in the output.

## Solutions to Common Issues
### No language and polyglot implementation was found on the module-path.
If you encounter an error like:
```
java.lang.IllegalStateException: No language and polyglot implementation was found on the module-path. Make sure at last one language is added to the module-path.
```
This indicates graalvm polyglot is not loaded correctly. The following workaround manually adds the polyglot module to the module path:
1. Run the following command to copy polyglot dependencies to a folder called `libs`:
   ```
   ./gradlew copyDependencies
   ```
   You can modify the target directory by changing the `copyDependencies` task in `build.gradle`.
2. Add `module-path` and `add-modules` to jvm options
   ```
   --module-path=/path/to/libs
   --add-modules=org.graalvm.polyglot
   ```

## License
This code is licensed under the Apache 2.0 License. See [LICENSE.txt](LICENSE.txt).

## Copyright
Copyright OpenSearch Contributors. See [NOTICE](NOTICE.txt) for details.
