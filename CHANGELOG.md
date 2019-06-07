5.5.2 (June 2019)
-----------------

- Bump omero-renderer and omero-common-test version.
- Ensure progress with indexer batch is always recorded.


5.5.1 (May 2019)
----------------

- Upgrade Javassist for Java 11 compatibility.

5.5.0 (May 2019)
----------------

- Revert "Have graph path report reference decoupled Javadoc."
- Have session provider use Java helper instead of Guava.
- Remove wildcard from git ignore exclusion.
- Have graph path report reference decoupled Javadoc.
- Replace http by https.
- Fix ConfigImpl.getConfigDefaults().
- Run units test in Travis.
- Fix Javadoc warnings.
- Partially migrate Properties file from the openmicroscopy repository.
- Add License file.
- Fix code URL for GraphPathReport's move to new repository.
- Introduce stand-in indexer
- Deprecate setCaseSentivice in favor of new setCaseSensitive.
- Safely order repository file deletions.
- Update ACL voter for linking to system objects
- Open files as read-only if read-write would fail.
- Cast ByteBuffer to Byte for JDK8 support.
- Reimplement UpdateImpl.indexObject using Quartz in Blitz.
- Extend Javadoc of new full-text indexer stand-in.
- Index numeric annotation values.
- Use new Gradle build system.
- Extract omero-server from the openmicroscopy repository.
