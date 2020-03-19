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
