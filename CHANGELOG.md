5.6.5 (December 2022)
---------------------

- Bump to TestNG 7.5 ([#148](https://github.com/ome/omero-server/pull/148))
- Fix logging for OmeroFilePathResolver ([#145](https://github.com/ome/omero-server/pull/145))
- Bump omero-renderer version to 5.5.11
- Bump omero-common-test version to 5.6.0

5.6.4 (June 2022)
-----------------

- Add the max_plane_float_override config variable to ConfiguredTileSizes ([#141](https://github.com/ome/omero-server/pull/141))
- Handle overflow error when calculating stats on large planes ([#128](https://github.com/ome/omero-server/pull/128))
- Bump omero-renderer version to 5.5.10
- Bump omero-common-test version to 5.5.10
- Bump org.openmicroscopy.project plugin to 5.5.4 ([#143](https://github.com/ome/omero-server/pull/143))
- Add Gradle publication workflow ([#143](https://github.com/ome/omero-server/pull/143))

5.6.3 (April 2022)
------------------

- Bump omero-renderer version to 5.5.9
- Bump omero-common-test version to 5.5.9

5.6.2 (April 2022)
------------------

- Unify ice version ([#138](https://github.com/ome/omero-server/pull/138))
- Upgrade commons-io from 1.x to 2.6 ([#135](https://github.com/ome/omero-server/pull/135))
- Handle Well Annotation references during import ([#127](https://github.com/ome/omero-server/pull/127))
- Add Bio-Formats version metadata to configuration  ([#126](https://github.com/ome/omero-server/pull/126))
- Use maven central ([#123](https://github.com/ome/omero-server/pull/123))
- Use Gradle 6 ([#120](https://github.com/ome/omero-server/pull/120))
- Use GitHub Actions ([#119](https://github.com/ome/omero-server/pull/119))
- Bump omero-renderer version to 5.5.8
- Bump omero-common-test version to 5.5.8

5.6.1 (September 2020)
----------------------

- Fix secure mail configuration ([#110](https://github.com/ome/omero-server/pull/110))
- New properties to configure JDBC ([#108](https://github.com/ome/omero-server/pull/108))
- Document time units for durations ([#105](https://github.com/ome/omero-server/pull/105))
- Minor test fixes ([#104](https://github.com/ome/omero-server/pull/104))
- Bump omero-renderer version to 5.5.7
- Bump omero-common-test version to 5.5.7

5.6.0 (July 2020)
-----------------

- Fail early if we are in read-only mode ([#101](https://github.com/ome/omero-server/pull/101))
- Add note about binary access restrictions on groups ([#100](https://github.com/ome/omero-server/pull/100))
- Restore exception handling in full-text indexer ([#99](https://github.com/ome/omero-server/pull/99))
- Move check into helper of admin privileges from user config ([#92](https://github.com/ome/omero-server/pull/92))
- More cleanly adapt basic security system for read-only mode ([#91](https://github.com/ome/omero-server/pull/91))
- Remove junit dependency ([#90](https://github.com/ome/omero-server/pull/90))
- Bump omero-renderer version to 5.5.6
- Bump omero-common-test version to 5.5.6


5.5.6 (March 2020)
------------------

- Allow sudo sessions on read-only servers
  ([#71](https://github.com/ome/omero-server/pull/71))
- Allow "Fake" to be added to the database format table of image readers
  ([#75](https://github.com/ome/omero-server/pull/75))
- Have raw file store skip flush when in read-only mode
  ([#78](https://github.com/ome/omero-server/pull/78))
- For sessions allow setting agent, also system maximum for timeouts
  ([#79](https://github.com/ome/omero-server/pull/79))
- Display unit test output instead of caching it
  ([#81](https://github.com/ome/omero-server/pull/81))
- Security vulnerability fixes for
  [2019-SV2](https://www.openmicroscopy.org/security/advisories/2019-SV2-group-permissions/),
  [2019-SV3](https://www.openmicroscopy.org/security/advisories/2019-SV3-user-privacy/),
  [2019-SV5](https://www.openmicroscopy.org/security/advisories/2019-SV5-bypass-filters/) and
  [2019-SV6](https://www.openmicroscopy.org/security/advisories/2019-SV6-group-owner-context/)
- Bump omero-renderer version to 5.5.5
- Bump omero-common-test version to 5.5.5

5.5.5 (December 2019)
---------------------

- Bump omero-renderer version to 5.5.3
- Retry failed full text indexing steps (#67)
- Index Channel and Fileset metadata (#57)
- Handle LDAP timeouts gracefully (#66)

5.5.4 (July 2019)
-----------------

- Bump OMERO dependencies via renderer 5.5.2
- Minor full text bridge stability fix (#60)

5.5.3 (June 2019)
-----------------

- Add LocalSession.getSessionQuietly to permit session timeouts (#56)
- Improve LDAP exception messages (#58)

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
