/**
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.api.client.auth.oauth2.Credential;
import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemBase;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.joda.time.Instant;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// TODO(user): add tests for multi-threaded reads/writes

/**
 * Integration tests for GoogleCloudStorageFileSystem class.
 *
 * This class reuses most of the tests defined in GoogleCloudStorageIntegrationTest.
 * It achieves that by overriding key methods (for example, open()) and mapping
 * their parameters, usually bucket+object, to GCS FS path URI and invoking
 * the corresponding function in GCS FS layer.
 * for example,
 * In GCS tests, open(bucket, object) calls GoogleCloudStorage.open(bucket, object).
 * In GCS FS tests, open(bucket, object) first maps bucket+object to path URI
 * and then calls GoogleCloudStorageFileSystem.open(path).
 * This approach allows a large number of test cases and their test data
 * to be reused instead of duplicating them in this class.
 *
 * In some cases, the methods in the base class do not nicely map to the
 * corresponding concepts in this layer (for example, copy-metadata)
 * therefore such tests are rewritten in this class and the corresponding
 * tests from base class are suppressed (by overriding them with empty body).
 *
 * The same practice (reuse most, suppress rest) is followed along the
 * following class inheritance hierarchy:
 * -- GoogleCloudStorageIntegrationTest
 * -- GoogleCloudStorageFileSystemIntegrationTest
 * -- GoogleHadoopGlobalRootedFileSystemIntegrationTest
 */
@RunWith(JUnit4.class)
public class GoogleCloudStorageFileSystemIntegrationTest
    extends GoogleCloudStorageIntegrationTest {

  // GCS FS test access instance.
  protected static GoogleCloudStorageFileSystem gcsfs;

  /**
   * Perform initialization once before tests are run.
   */
  @BeforeClass
  public static void beforeAllTests()
      throws IOException {
    if (gcsfs == null) {
      Credential credential = GoogleCloudStorageIntegrationTest.getCredential();
      String appName = GoogleCloudStorageIntegrationTest.APP_NAME;
      String projectId = System.getenv(GoogleCloudStorageIntegrationTest.GCS_TEST_PROJECT_ID);
      Assert.assertNotNull(projectId);

      GoogleCloudStorageFileSystemOptions.Builder optionsBuilder =
          GoogleCloudStorageFileSystemOptions.newBuilder();

      optionsBuilder
          .setIsMetadataCacheEnabled(true)
          .getCloudStorageOptionsBuilder()
          .setAppName(appName)
          .setProjectId(projectId)
          .getWriteChannelOptionsBuilder()
          .setFileSizeLimitedTo250Gb(GoogleHadoopFileSystemBase.GCS_FILE_SIZE_LIMIT_250GB_DEFAULT)
          .setUploadBufferSize(GoogleHadoopFileSystemBase.WRITE_BUFFERSIZE_DEFAULT);

      gcsfs = new GoogleCloudStorageFileSystem(
          credential,
          optionsBuilder.build());

      gcsit = new GoogleCloudStorageFileSystemIntegrationTest();

      // Ensures that we do not accidentally end up testing wrong functionality.
      gcs = null;

      postCreateInit();
    }
  }

  /**
   * Perform initialization after creating test instances.
   */
  public static void postCreateInit()
      throws IOException {
    GoogleCloudStorageIntegrationTest.postCreateInit();
  }

  /**
   * Perform clean-up once after all tests are turn.
   */
  @AfterClass
  public static void afterAllTests()
      throws IOException {
    GoogleCloudStorageIntegrationTest.afterAllTests();
    if (gcsfs != null) {
      deleteOldTestBuckets();
      gcsfs.close();
      gcsfs = null;
    }
  }

  // -----------------------------------------------------------------
  // Overridden methods to ensure that GCS FS functionality is used
  // instead of GCS functionality where applicable.
  // -----------------------------------------------------------------

  /**
   * Actual logic for validating the GoogleCloudStorageFileSystem-specific FileInfo returned by
   * getItemInfo() or listFileInfo().
   */
  private void validateFileInfoInternal(
      String bucketName, String objectName, boolean expectedToExist, FileInfo fileInfo)
      throws IOException {
    Assert.assertEquals(expectedToExist, fileInfo.exists());

    long expectedSize = getExpectedObjectSize(objectName, expectedToExist);
    if (expectedSize != Long.MIN_VALUE) {
      Assert.assertEquals(expectedSize, fileInfo.getSize());
    }

    boolean expectedDirectory =
        (objectName == null) || FileInfo.objectHasDirectoryPath(objectName);
    Assert.assertEquals(
        String.format("isDirectory for bucketName '%s' objectName '%s'", bucketName, objectName),
        expectedDirectory, fileInfo.isDirectory());
    if (expectedToExist) {
      Instant currentTime = Instant.now();
      Instant fileCreationTime = new Instant(fileInfo.getCreationTime());

      // Check !isBefore and !isAfter to allow the timestamp to allow for "0 milliseconds elapsed".
      Assert.assertFalse(String.format(
          "stale file? testStartTime: %s, fileCreationTime: %s",
          testStartTime.toString(), fileCreationTime.toString()),
          fileCreationTime.isBefore(testStartTime));
      Assert.assertFalse(String.format(
          "unexpected creation-time: clock skew? currentTime: %s, fileCreationTime: %s",
          currentTime.toString(), fileCreationTime.toString()),
          fileCreationTime.isAfter(currentTime));
    } else {
      Assert.assertEquals(0, fileInfo.getCreationTime());
    }
    Assert.assertTrue(fileInfo.toString().length() > 0);
  }

  /**
   * Validates FileInfo for the given item.
   *
   * See {@link GoogleCloudStorageIntegrationTest.testListObjectNamesAndGetItemInfo()} for more
   * info.
   *
   * Note: The test initialization code creates objects as text files.
   * Each text file contains name of its associated object.
   * Therefore, size of an object == objectName.getBytes("UTF-8").length.
   */
  protected void validateGetItemInfo(String bucketName, String objectName, boolean expectedToExist)
      throws IOException {
    URI path = getPath(bucketName, objectName);
    FileInfo fileInfo = gcsfs.getFileInfo(path);
    Assert.assertEquals(path, fileInfo.getPath());
    validateFileInfoInternal(bucketName, objectName, expectedToExist, fileInfo);
  }

  /**
   * Validates FileInfo returned by listFileInfo().
   *
   * See {@link GoogleCloudStorageIntegrationTest.testListObjectNamesAndGetItemInfo()} for more
   * info.
   */
  protected void validateListNamesAndInfo(String bucketName, String objectNamePrefix,
      boolean pathExpectedToExist, String... expectedListedNames)
      throws IOException {

    boolean childPathsExpectedToExist =
        pathExpectedToExist && (expectedListedNames != null);
    boolean listRoot = bucketName == null;

    // Prepare list of expected paths.
    List<URI> expectedPaths = new ArrayList<>();
    // Also maintain a backwards mapping to keep track of which of "expectedListedNames" and
    // "bucketName" is associated with each path, so that we can supply validateFileInfoInternal
    // with the objectName and thus enable it to lookup the internally stored expected size,
    // directory status, etc., of the associated FileStatus.
    Map<URI, String[]> pathToComponents = new HashMap<>();
    if (childPathsExpectedToExist) {
      for (String expectedListedName : expectedListedNames) {
        String[] pathComponents = new String[2];
        if (listRoot) {
          pathComponents[0] = expectedListedName;
          pathComponents[1] = null;
          expectedPaths.add(getPath(expectedListedName, null));
        } else {
          pathComponents[0] = bucketName;
          pathComponents[1] = expectedListedName;
        }
        URI expectedPath = getPath(pathComponents[0], pathComponents[1]);
        expectedPaths.add(expectedPath);
        pathToComponents.put(expectedPath, pathComponents);
      }
    }

    // Get list of actual paths.
    URI path = getPath(bucketName, objectNamePrefix);
    List<FileInfo> fileInfos;

    try {
      fileInfos = gcsfs.listFileInfo(path);
      if (!pathExpectedToExist) {
        Assert.fail("Expected FileNotFoundException for path: " + path);
      }
    } catch (FileNotFoundException e) {
      fileInfos = new ArrayList<>();
      if (pathExpectedToExist) {
        Assert.fail("Did not expect FileNotFoundException for path: " + path);
      }
    }

    List<URI> actualPaths = new ArrayList<>();
    for (FileInfo fileInfo : fileInfos) {
      Assert.assertEquals(
          "File exists? : " + fileInfo.getPath(),
          childPathsExpectedToExist, fileInfo.exists());
      if (fileInfo.exists()) {
        actualPaths.add(fileInfo.getPath());
        String[] uriComponents = pathToComponents.get(fileInfo.getPath());
        if (uriComponents != null) {
          // Only do fine-grained validation for the explicitly expected paths.
          validateFileInfoInternal(uriComponents[0], uriComponents[1], true, fileInfo);
        }
      }
    }

    if (listRoot) {
      Set<URI> actualPathsSet = new HashSet<>(actualPaths);
      for (URI expectedPath : expectedPaths) {
        Assert.assertTrue(
            String.format("expected: <%s> in: <%s>", expectedPath, actualPathsSet),
            actualPathsSet.contains(expectedPath));
      }
    } else {
      Collections.sort(expectedPaths);
      Collections.sort(actualPaths);
      Assert.assertArrayEquals(expectedPaths.toArray(), actualPaths.toArray());
    }

    // Now re-verify using listFileNames instead of listFileInfo.
    FileInfo baseInfo = gcsfs.getFileInfo(path);
    List<URI> listedUris = gcsfs.listFileNames(baseInfo);

    if (!baseInfo.isDirectory() && !baseInfo.exists()) {
      // This is one case which differs between listFileInfo and listFileNames; listFileInfo will
      // throw an exception for non-existent paths, while listFileNames will *always* return the
      // unaltered path itself as long as it's not a directory. If it's a non-existent directory
      // path, it returns an empty list, as opposed to this case, where it's a list of size 1.
      expectedPaths.add(path);
    }

    if (listRoot) {
      // By nature of the globally-visible GCS root (gs://), as long as we share a project for
      // multiple testing purposes there's no way to know the exact expected contents to be listed,
      // because other people/tests may have their own buckets alongside those created by this test.
      // So, we just check that the expectedPaths are at least a subset of the listed ones.
      Set<URI> actualPathsSet = new HashSet<>(listedUris);
      for (URI expectedPath : expectedPaths) {
        Assert.assertTrue(
            String.format("expected: <%s> in: <%s>", expectedPath, actualPathsSet),
            actualPathsSet.contains(expectedPath));
      }
    } else {
      Collections.sort(listedUris);
      Assert.assertArrayEquals(expectedPaths.toArray(), listedUris.toArray());
    }
  }

  /**
   * Opens the given object for reading.
   */
  @Override
  protected SeekableReadableByteChannel open(String bucketName, String objectName)
      throws IOException {
    URI path = getPath(bucketName, objectName);
    return gcsfs.open(path);
  }

  /**
   * Opens the given object for writing.
   */
  @Override
  protected WritableByteChannel create(String bucketName, String objectName)
      throws IOException {
    URI path = getPath(bucketName, objectName);
    return gcsfs.create(path);
  }

  /**
   * Creates a directory.
   */
  @Override
  protected void mkdir(String bucketName, String objectName)
      throws IOException {
    URI path = getPath(bucketName, objectName);
    gcsfs.mkdir(path);
  }

  /**
   * Creates a directory.
   */
  @Override
  protected void mkdir(String bucketName)
      throws IOException {
    URI path = getPath(bucketName, null);
    gcsfs.mkdir(path);
  }

  /**
   * Deletes the given item.
   */
  @Override
  protected void delete(String bucketName)
      throws IOException {
    URI path = getPath(bucketName, null);
    gcsfs.delete(path, false);
  }

  /**
   * Deletes the given object.
   */
  @Override
  protected void delete(String bucketName, String objectName)
      throws IOException {
    URI path = getPath(bucketName, objectName);
    gcsfs.delete(path, false);
  }

  /**
   * Deletes all objects from the given bucket.
   */
  @Override
  protected void clearBucket(String bucketName)
      throws IOException {
    URI path = getPath(bucketName, null);
    FileInfo pathInfo = gcsfs.getFileInfo(path);
    List<URI> fileNames = gcsfs.listFileNames(pathInfo);
    for (URI fileName : fileNames) {
      gcsfs.delete(fileName, true);
    }
  }

  // -----------------------------------------------------------------
  // Overridable methods added by this class.
  // -----------------------------------------------------------------

  /**
   * Renames src path to dst path.
   */
  protected boolean rename(URI src, URI dst)
      throws IOException {
    gcsfs.rename(src, dst);
    return true;
  }

  /**
   * Deletes the given path.
   */
  protected boolean delete(URI path, boolean recursive)
      throws IOException {
    gcsfs.delete(path, recursive);
    return true;
  }

  /**
   * Creates the given directory.
   */
  protected boolean mkdirs(URI path)
      throws IOException {
    gcsfs.mkdirs(path);
    return true;
  }

  /**
   * Indicates whether the given path exists.
   */
  protected boolean exists(URI path)
      throws IOException {
    return gcsfs.exists(path);
  }

  /**
   * Indicates whether the given path is directory.
   */
  protected boolean isDirectory(URI path)
      throws IOException {
    return gcsfs.getFileInfo(path).isDirectory();
  }

  // -----------------------------------------------------------------
  // Misc helpers
  // -----------------------------------------------------------------

  /**
   * Helper to construct a path.
   */
  protected static URI getPath(String bucketName, String objectName) {
    // 'true' for allowEmptyObjectName.
    URI path = GoogleCloudStorageFileSystem.getPath(bucketName, objectName, true);
    return path;
  }

  // -----------------------------------------------------------------
  // Tests added by this class.
  // -----------------------------------------------------------------

  /**
   * Contains data needed for testing the delete() operation.
   */
  private static class DeleteData {

    // Description of test case.
    String description;

    // Bucket component of the path to delete.
    String bucketName;

    // Object component of the path to delete.
    String objectName;

    // Indicates whether it is a recursive delete.
    boolean recursive;

    // Expected outcome; can return true, return false, or return exception of a certain type.
    MethodOutcome expectedOutcome;

    // Objects expected to exist after the operation.
    List<String> objectsExpectedToExist;

    // Objects expected to be deleted after the operation.
    List<String> objectsExpectedToBeDeleted;

    /**
     * Constructs an instance of the DeleteData class.
     */
    DeleteData(
        String description,
        String bucketName, String objectName, boolean recursive,
        MethodOutcome expectedOutcome,
        List<String> objectsExpectedToExist,
        List<String> objectsExpectedToBeDeleted) {

      this.description = description;
      this.bucketName = bucketName;
      this.objectName = objectName;
      this.recursive = recursive;
      this.expectedOutcome = expectedOutcome;
      this.objectsExpectedToExist = objectsExpectedToExist;
      this.objectsExpectedToBeDeleted = objectsExpectedToBeDeleted;
    }
  }

  /**
   * Validates delete().
   */
  @Test
  public void testDelete()
      throws IOException {
    deleteHelper(new DeletionBehavior() {
      @Override
      public MethodOutcome nonEmptyDeleteOutcome() {
        // GCSFS throws IOException on non-recursive delete of non-empty dir.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, IOException.class);
      }

      @Override
      public MethodOutcome nonExistentDeleteOutcome() {
        // GCSFS throws FileNotFoundException if deleting a non-existent file.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, FileNotFoundException.class);
      }
    });
  }

  /**
   * Tests listObjectNames() and getItemInfo().
   *
   * The data required for the 2 tests is expensive to create therefore
   * we combine the tests into one.
   *
   * TODO(user) : move test init code into @BeforeClass. Create data for
   * these 2 tests in its own bucket after createBucket() support is available.
   */
  @Test
  public void testListObjectNamesAndGetItemInfo()
      throws IOException {

    // Objects created for this test.
    String[] objectNames = {
        "o1",
        "o2",
        "d0/",
        "d1/o11",
        "d1/o12",
        "d1/d10/",
        "d1/d11/o111",
        "d2/o21",
        "d2/o22",
    };

    String dirDoesNotExist = "does-not-exist/";
    String objDoesNotExist = "does-not-exist";

    // -------------------------------------------------------
    // Create test objects.
    clearBucket(bucketName);
    createObjectsWithSubdirs(bucketName, objectNames);

    // -------------------------------------------------------
    // Tests for getItemInfo().
    // -------------------------------------------------------

    // Verify that getItemInfo() returns correct info for each object.
    for (String objectName : objectNames) {
      validateGetItemInfo(bucketName, objectName, true);
    }

    // Verify that getItemInfo() returns correct info for bucket.
    validateGetItemInfo(bucketName, null, true);

    // Verify that getItemInfo() returns correct info for a non-existent object.
    validateGetItemInfo(bucketName, dirDoesNotExist, false);

    // Verify that getItemInfo() returns correct info for a non-existent bucket.
    validateGetItemInfo(bucketName, objDoesNotExist, false);

    // -------------------------------------------------------
    // Tests for listObjectNames().
    // -------------------------------------------------------

    // Verify that listObjectNames() returns correct names for each case below.

    // At root.
    validateListNamesAndInfo(bucketName, null, true, "o1", "o2", "d0/", "d1/", "d2/");
    validateListNamesAndInfo(bucketName, "", true, "o1", "o2", "d0/", "d1/", "d2/");

    // At d0.
    validateListNamesAndInfo(bucketName, "d0/", true);

    // At o1.
    validateListNamesAndInfo(bucketName, "o1", true, "o1");

    // TODO(user) : bug in GCS? fails only when running gcsfs tests?
    // validateListNamesAndInfo(bucketName, "d0", true, "d0/");

    // At d1.
    validateListNamesAndInfo(bucketName, "d1/", true, "d1/o11", "d1/o12", "d1/d10/", "d1/d11/");

    // TODO(user) : bug in GCS? fails only when running gcsfs tests?
    // validateListNamesAndInfo(bucketName, "d1", true, "d1/");

    // At d1/d11.
    validateListNamesAndInfo(bucketName, "d1/d11/", true, "d1/d11/o111");

    // TODO(user) : bug in GCS? fails only when running gcsfs tests?
    // validateListNamesAndInfo(bucketName, "d1/d11", true, "d1/d11/");

    // At d2.
    validateListNamesAndInfo(bucketName, "d2/", true, "d2/o21", "d2/o22");

    // TODO(user) : bug in GCS? fails only when running gcsfs tests?
    // validateListNamesAndInfo(bucketName, "d2", true, "d2/");

    // At non-existent path.
    validateListNamesAndInfo(bucketName, dirDoesNotExist, false);
    validateListNamesAndInfo(bucketName, objDoesNotExist, false);
    validateListNamesAndInfo(objDoesNotExist, objDoesNotExist, false);


    // -------------------------------------------------------
    // Tests for listObjectNames().
    // -------------------------------------------------------
    validateListNamesAndInfo(null, null, true, bucketName, otherBucketName);

    // Validate one special case.
    Assert.assertTrue(!GoogleCloudStorageItemInfo.ROOT_INFO.equals("non-item-info"));
  }

  /**
   * Validates simple write and read operations.
   */
  @Test
  public void testWriteAndReadObject()
      throws IOException {
    String message = "Hello world!\n";

    // Write an object.
    int numBytesWritten = writeTextFile(bucketName, objectName, message);

    // Read the whole object.
    String message2 = readTextFile(bucketName, objectName, 0, numBytesWritten, true);

    // Verify we read what we wrote.
    Assert.assertEquals(message, message2);
  }

  /**
   * Validates partial reads.
   */
  @Test
  public void testReadPartialObject()
      throws IOException {
    String message = "Hello world!\n";

    // Write an object.
    writeTextFile(bucketName, objectName, message);

    // Read the whole object in 2 parts.
    int offset = 6;  // chosen arbitrarily.
    String message1 = readTextFile(bucketName, objectName, 0, offset, false);
    String message2 = readTextFile(bucketName, objectName, offset, message.length() - offset, true);

    // Verify we read what we wrote.
    Assert.assertEquals("partial read mismatch", message.substring(0, offset), message1);
    Assert.assertEquals("partial read mismatch", message.substring(offset), message2);
  }

  /**
   * Validates that we cannot open a non-existent object.
   */
  @Test
  public void testOpenNonExistent()
      throws IOException {
    try {
      readTextFile(getUniqueBucketName(), objectName, 0, 100, true);
      Assert.fail("Expected FileNotFoundException");
    } catch (FileNotFoundException expected) {
      // Expected.
    }
  }

  /**
   * Validates delete().
   */
  public void deleteHelper(DeletionBehavior behavior)
      throws IOException {

    // Objects created for this test.
    String[] objectNames = {
      "f1",
      "d0/",
      "d1/f1",
      "d1/d0/",
      "d1/d11/f1",
    };

    // -------------------------------------------------------
    // Create test objects.
    gcsit.clearBucket(bucketName);
    gcsit.createObjectsWithSubdirs(bucketName, objectNames);

    // The same set of objects are also created under a bucket that
    // we will delete as a part of the test.
    String tempBucket = GoogleCloudStorageIntegrationTest.createTempBucket();
    gcsit.createObjectsWithSubdirs(tempBucket, objectNames);

    // -------------------------------------------------------
    // Initialize test data.
    List<DeleteData> deleteData = new ArrayList<>();
    String doesNotExist = "does-not-exist";
    String dirDoesNotExist = "does-not-exist";

    // Delete an item that does not exist.
    deleteData.add(new DeleteData(
        "Delete an object that does not exist: file",
        bucketName, doesNotExist, false,
        behavior.nonExistentDeleteOutcome(),  // expected outcome
        null,  // expected to exist
        null));  // expected to be deleted
    deleteData.add(new DeleteData(
        "Delete an object that does not exist: dir",
        bucketName, dirDoesNotExist, false,
        behavior.nonExistentDeleteOutcome(),  // expected outcome
        null,  // expected to exist
        null));  // expected to be deleted
    deleteData.add(new DeleteData(
        "Delete a bucket that does not exist",
        doesNotExist, doesNotExist, false,
        behavior.nonExistentDeleteOutcome(),  // expected outcome
        null,  // expected to exist
        null));  // expected to be deleted

    // Delete an empty directory.
    deleteData.add(new DeleteData(
        "Delete an empty directory",
        bucketName, "d0/", true,
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),  // expected outcome
        null,  // expected to exist
        Lists.newArrayList("d0/")));  // expected to be deleted

    // Delete a non-empty directory (recursive == false).
    deleteData.add(new DeleteData(
        "Delete a non-empty directory (recursive == false)",
        bucketName, "d1/", false,
        behavior.nonEmptyDeleteOutcome(),  // expected outcome
        Lists.newArrayList("d1/", "d1/f1", "d1/d0/", "d1/d11/f1"),  // expected to exist
        null));  // expected to be deleted

    // Delete a non-empty directory (recursive == true).
    deleteData.add(new DeleteData(
        "Delete a non-empty directory (recursive == true)",
        bucketName, "d1/", true,
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),  // expected outcome
        null,  // expected to exist
        Lists.newArrayList("d1/", "d1/f1", "d1/d0/", "d1/d11/f1")));  // expected to be deleted

    // Delete a non-empty bucket (recursive == false).
    deleteData.add(new DeleteData(
        "Delete a non-empty bucket (recursive == false)",
        tempBucket, null, false,
        behavior.nonEmptyDeleteOutcome(),  // expected outcome
        Lists.newArrayList(
            // expected to exist
            "f1", "d0/", "d1/", "d1/f1", "d1/d0/", "d1/d11/f1"),
        null));  // expected to be deleted

    // Delete a non-empty bucket (recursive == true).
    deleteData.add(new DeleteData(
        "Delete a non-empty bucket (recursive == true)",
        tempBucket, null, true,
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),  // expected outcome
        null,  // expected to exist
        Lists.newArrayList(
            // expected to be deleted
            "f1", "d0/", "d1/", "d1/f1", "d1/d0/", "d1/d11/f1")));

    // -------------------------------------------------------
    // Call delete() for each path and verify the expected behavior.
    for (DeleteData dd : deleteData) {

      // Verify that items that we expect to delete are present before the operation.
      assertPathsExist(dd.description, dd.bucketName, dd.objectsExpectedToBeDeleted, true);

      URI path = getPath(dd.bucketName, dd.objectName);
      try {

        // Perform the delete operation.
        boolean result = delete(path, dd.recursive);

        if (result) {
          Assert.assertEquals(String.format(
              "Unexpected result for path: %s : %s :: expected %s, actually returned true.",
              path, dd.description, dd.expectedOutcome.toString()),
              dd.expectedOutcome.getType(), MethodOutcome.Type.RETURNS_TRUE);
        } else {
          Assert.assertEquals(String.format(
              "Unexpected result for path: %s : %s :: expected %s, actually returned false.",
              path, dd.description, dd.expectedOutcome.toString()),
              dd.expectedOutcome.getType(), MethodOutcome.Type.RETURNS_FALSE);
        }
      } catch (Exception e) {
        Assert.assertEquals(String.format(
            "Unexpected result for path: %s : %s :: expected %s, actually threw exception %s.",
            path, dd.description, dd.expectedOutcome.toString(),
            Throwables.getStackTraceAsString(e)),
            dd.expectedOutcome.getType(), MethodOutcome.Type.THROWS_EXCEPTION);
      }

      // Verify that items that we expect to exist are present.
      assertPathsExist(dd.description, dd.bucketName, dd.objectsExpectedToExist, true);

      // Verify that items that we expect to be deleted are not present.
      assertPathsExist(dd.description, dd.bucketName, dd.objectsExpectedToBeDeleted, false);
    }
  }

  /**
   * Call mkdir then create a file with the same name, not including the trailing slash for the
   * param to mkdir. The create should fail.
   */
  @Test
  public void testMkdirAndCreateFileOfSameName()
      throws IOException, URISyntaxException {
    String uniqueDirName = GoogleCloudStorageIntegrationTest.getUniqueDirectoryObjectName();
    mkdir(bucketName, uniqueDirName + GoogleCloudStorage.PATH_DELIMITER);
    try {
      writeTextFile(bucketName, uniqueDirName, "hello world");
      Assert.fail(
          "Expected IOException for create() of object with same name as existing directory.");
    } catch (IOException ioe) {

      Assert.assertTrue(String.format("unexpected exception: %s\n%s",
              ioe.getMessage(), Throwables.getStackTraceAsString(ioe)),
          ioe.getMessage().matches(".*(A directory with that name exists|Is a directory).*"));
    }
    delete(bucketName, uniqueDirName);
  }

  /**
   * Validates mkdirs().
   */
  @Test
  public void testMkdirs()
      throws IOException, URISyntaxException {
    mkdirsHelper(new MkdirsBehavior() {
      @Override
      public MethodOutcome mkdirsRootOutcome() {
        return new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE);
      }

      @Override
      public MethodOutcome fileAlreadyExistsOutcome() {
        return new MethodOutcome(MethodOutcome.Type.THROWS_EXCEPTION, IOException.class);
      }
    });
  }

  /**
   * Validates mkdirs().
   */
  public void mkdirsHelper(MkdirsBehavior behavior)
      throws IOException, URISyntaxException {

    // Objects created for this test.
    String[] objectNames = {
      "f1",
      "d0/",
      "d1/f11",
    };

    // -------------------------------------------------------
    // Create test objects.
    gcsit.clearBucket(bucketName);
    gcsit.createObjectsWithSubdirs(bucketName, objectNames);

    // -------------------------------------------------------
    // Initialize test data.
    // key == directory path to pass to mkdirs()
    // val == Expected outcome
    Map<URI, MethodOutcome> dirData = new HashMap<>();

    // Verify that attempt to create root dir does not throw (no-op).
    dirData.put(GoogleCloudStorageFileSystem.GCS_ROOT, behavior.mkdirsRootOutcome());

    // Verify that no exception is thrown when directory already exists.
    dirData.put(getPath(bucketName, "d0/"), new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));
    dirData.put(getPath(bucketName, "d0"), new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));

    // Expect IOException if a file with the given name already exists.
    dirData.put(
        getPath(bucketName, "f1/"), behavior.fileAlreadyExistsOutcome());
    dirData.put(
        getPath(bucketName, "d1/f11/d3/"), behavior.fileAlreadyExistsOutcome());

    // Some intermediate directories exist (but not all).
    dirData.put(getPath(bucketName, "d1/d2/d3/"),
                new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));

    // No intermediate directories exist.
    dirData.put(getPath(bucketName, "dA/dB/dC/"),
                new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));

    // Trying to create the same dirs again is a no-op.
    dirData.put(getPath(bucketName, "dA/dB/dC/"),
                new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));

    // Make paths that include making a top-level directory (bucket).
    String uniqueBucketName = GoogleCloudStorageIntegrationTest.getUniqueBucketName();
    GoogleCloudStorageIntegrationTest.addToDeleteBucketList(uniqueBucketName);
    dirData.put(getPath(uniqueBucketName, null),
                new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));

    // Create the same bucket again, should be no-op.
    dirData.put(getPath(uniqueBucketName, null),
                new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));

    // Make a path where the bucket is a non-existent parent directory.
    String uniqueBucketName2 = GoogleCloudStorageIntegrationTest.getUniqueBucketName();
    GoogleCloudStorageIntegrationTest.addToDeleteBucketList(uniqueBucketName2);
    dirData.put(getPath(uniqueBucketName2, "foo/bar"),
                new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE));

    // Call mkdirs() for each path and verify the expected behavior.
    for (URI path : dirData.keySet()) {
      MethodOutcome expectedOutcome = dirData.get(path);
      try {
        boolean result = mkdirs(path);
        if (result) {
          Assert.assertEquals(String.format(
              "Unexpected result for path: %s : expected %s, actually returned true.",
              path, expectedOutcome.toString()),
              expectedOutcome.getType(), MethodOutcome.Type.RETURNS_TRUE);

          // Assert that all of the sub-dirs have been created.
          List<URI> subDirPaths = getSubDirPaths(path);
          for (URI subDirPath : subDirPaths) {
            Assert.assertTrue(String.format(
                "Sub-path %s of path %s not found or not a dir",
                subDirPath, path),
                exists(subDirPath) && isDirectory(subDirPath));
          }
        } else {
          Assert.assertEquals(String.format(
              "Unexpected result for path: %s : expected %s, actually returned false.",
              path, expectedOutcome.toString()),
              expectedOutcome.getType(), MethodOutcome.Type.RETURNS_FALSE);
        }
      } catch (Exception e) {
        Assert.assertEquals(String.format(
            "Unexpected result for path: %s : expected %s, actually threw exception %s.",
            path, expectedOutcome.toString(),
            Throwables.getStackTraceAsString(e)),
            expectedOutcome.getType(), MethodOutcome.Type.THROWS_EXCEPTION);
      }
    }
  }

  /**
   * Validates getFileInfos().
   */
  @Test
  public void testGetFileInfos()
      throws IOException, URISyntaxException {
    // Objects created for this test.
    String[] objectNames = {
      "f1",
      "d0/",
    };

    // -------------------------------------------------------
    // Create test objects.
    gcsit.clearBucket(bucketName);
    gcsit.createObjectsWithSubdirs(bucketName, objectNames);

    List<URI> pathsToGet = new ArrayList<>();
    // Mix up the types of the paths to ensure the method will return the values in the same order
    // as their respective input parameters regardless of whether some are ROOT, directories, etc.
    pathsToGet.add(getPath(bucketName, "nonexistent"));
    pathsToGet.add(getPath(bucketName, "f1"));
    pathsToGet.add(getPath(null, null));
    pathsToGet.add(getPath(bucketName, "d0"));
    pathsToGet.add(getPath(bucketName, null));

    List<FileInfo> fileInfos = gcsfs.getFileInfos(pathsToGet);

    // First one doesn't exist.
    Assert.assertFalse(fileInfos.get(0).exists());
    Assert.assertEquals(new StorageResourceId(bucketName, "nonexistent"),
        fileInfos.get(0).getItemInfo().getResourceId());

    // Second one exists and is a StorageObject.
    Assert.assertTrue(fileInfos.get(1).exists());
    Assert.assertTrue(fileInfos.get(1).getItemInfo().getResourceId().isStorageObject());
    Assert.assertEquals(new StorageResourceId(bucketName, "f1"),
        fileInfos.get(1).getItemInfo().getResourceId());

    // Third one exists and is root.
    Assert.assertTrue(fileInfos.get(2).exists());
    Assert.assertTrue(fileInfos.get(2).isGlobalRoot());

    // Fourth one exists, but had to be auto-converted into a directory path.
    Assert.assertTrue(fileInfos.get(3).exists());
    Assert.assertTrue(fileInfos.get(3).isDirectory());
    Assert.assertTrue(fileInfos.get(3).getItemInfo().getResourceId().isStorageObject());
    Assert.assertEquals(new StorageResourceId(bucketName, "d0/"),
        fileInfos.get(3).getItemInfo().getResourceId());

    // Fifth one is a bucket.
    Assert.assertTrue(fileInfos.get(4).exists());
    Assert.assertTrue(fileInfos.get(4).isDirectory());
    Assert.assertTrue(fileInfos.get(4).getItemInfo().getResourceId().isBucket());
    Assert.assertEquals(new StorageResourceId(bucketName),
        fileInfos.get(4).getItemInfo().getResourceId());
  }

  /**
   * Contains data needed for testing the rename() operation.
   */
  private static class RenameData {

    // Description of test case.
    String description;

    // Bucket component of the src path.
    String srcBucketName;

    // Object component of the src path.
    String srcObjectName;

    // Bucket component of the dst path.
    String dstBucketName;

    // Object component of the dst path.
    String dstObjectName;

    // Expected outcome; can return true, return false, or return exception of a certain type.
    MethodOutcome expectedOutcome;

    // Objects expected to exist in src bucket after the operation.
    List<String> objectsExpectedToExistSrc;

    // Objects expected to exist in dst bucket after the operation.
    List<String> objectsExpectedToExistDst;

    // Objects expected to be deleted after the operation.
    List<String> objectsExpectedToBeDeleted;

    /**
     * Constructs an instance of the RenameData class.
     */
    RenameData(String description,
        String srcBucketName, String srcObjectName,
        String dstBucketName, String dstObjectName,
        MethodOutcome expectedOutcome,
        List<String> objectsExpectedToExistSrc,
        List<String> objectsExpectedToExistDst,
        List<String> objectsExpectedToBeDeleted) {

      this.description = description;
      this.srcBucketName = srcBucketName;
      this.srcObjectName = srcObjectName;
      this.dstBucketName = dstBucketName;
      this.dstObjectName = dstObjectName;
      this.expectedOutcome = expectedOutcome;
      this.objectsExpectedToExistSrc = objectsExpectedToExistSrc;
      this.objectsExpectedToExistDst = objectsExpectedToExistDst;
      this.objectsExpectedToBeDeleted = objectsExpectedToBeDeleted;
    }
  }

  /**
   * Validates rename().
   */
  @Test
  public void testRename()
      throws IOException {
    renameHelper(new RenameBehavior() {
      @Override
      public MethodOutcome renameFileIntoRootOutcome() {
        // GCSFS throws IOException on rename into root.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, IOException.class);
      }

      @Override
      public MethodOutcome renameRootOutcome() {
        // GCSFS throws IllegalArgumentException on rename of root.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, IllegalArgumentException.class);
      }

      @Override
      public MethodOutcome nonExistentSourceOutcome() {
        // GCSFS throws FileNotFoundException on nonexistent src.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, FileNotFoundException.class);
      }

      @Override
      public MethodOutcome destinationFileExistsSrcIsFileOutcome() {
        // GCSFS throws IOException if dst already exists, is a file, and src is also a file.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, IOException.class);
      }

      @Override
      public MethodOutcome destinationFileExistsSrcIsDirectoryOutcome() {
        // GCSFS throws IOException if dst already exists, is a file, and src is a directory.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, IOException.class);
      }

      @Override
      public MethodOutcome nonExistentDestinationFileParentOutcome() {
        // GCSFS throws FileNotFoundException if a parent of file dst doesn't exist.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, FileNotFoundException.class);
      }

      @Override
      public MethodOutcome nonExistentDestinationDirectoryParentOutcome() {
        // GCSFS throws FileNotFoundException if a parent of directory dst doesn't exist.
        return new MethodOutcome(
            MethodOutcome.Type.THROWS_EXCEPTION, FileNotFoundException.class);
      }
    });
  }

  /**
   * Validates rename().
   */
  protected void renameHelper(RenameBehavior behavior)
      throws IOException {

    String uniqueDir = GoogleCloudStorageIntegrationTest.getUniqueDirectoryObjectName() +
        GoogleCloudStorage.PATH_DELIMITER;
    String uniqueFile = uniqueDir + "f1";

    // Objects created for this test.
    String[] objectNames = {
      "f1",
      "f2",
      "d0/",
      "d0-a/",
      "d0-b/",
      "d1/f1",
      "d1/d0/",
      "d1/d11/f1",
      "d1-a/f1",
      "d1-b/f1",
      "d1-c/f1",
      "d1-d/f1",
      "d1-e/f1",
      "d1-f/f1",
      "d1-g/f1",
      "d1-h/f1",
      "d1-i/f1",
      uniqueFile,
      "td0-a/",
      "td0-b/",
      "n1-src/d1/f1",
      "n1-dst/",
      "n2-src/d0/",
      "n2-src/f1",
      "n2-src/d1/f1",
      "n2-src/d2/d21/d211/f1",
      "n2-dst/",
      "n2-dst/f1"
    };

    // Objects created in other bucket for this test.
    String[] otherObjectNames = {
      "td0/"
    };

    // -------------------------------------------------------
    // Create test objects.
    gcsit.clearBucket(bucketName);
    gcsit.createObjectsWithSubdirs(bucketName, objectNames);
    gcsit.createObjectsWithSubdirs(otherBucketName, otherObjectNames);

    // -------------------------------------------------------
    // Initialize test data.
    List<RenameData> renameData = new ArrayList<>();
    String doesNotExist = "does-not-exist";
    String dirDoesNotExist = "does-not-exist";

    // TODO(user) : add test case for dst under src (not allowed)

    // src == root.
    renameData.add(new RenameData(
        "src == root",
        null, null,
        otherBucketName, doesNotExist,
        behavior.renameRootOutcome(),  // expected outcome
        null,  // expected to exist in src
        null,  // expected to exist in dst
        null));  // expected to be deleted

    // src does not exist.
    renameData.add(new RenameData(
        "src does not exist: 1",
        bucketName, doesNotExist,
        otherBucketName, doesNotExist,
        behavior.nonExistentSourceOutcome(),  // expected outcome
        null,  // expected to exist in src
        null,  // expected to exist in dst
        null));  // expected to be deleted
    renameData.add(new RenameData(
        "src does not exist: 2",
        bucketName, dirDoesNotExist,
        otherBucketName, dirDoesNotExist,
        behavior.nonExistentSourceOutcome(),  // expected outcome
        null,  // expected to exist in src
        null,  // expected to exist in dst
        null));  // expected to be deleted
    renameData.add(new RenameData(
        "src does not exist: 3",
        doesNotExist, doesNotExist,
        otherBucketName, doesNotExist,
        behavior.nonExistentSourceOutcome(),  // expected outcome
        null,  // expected to exist in src
        null,  // expected to exist in dst
        null));  // expected to be deleted

    // dst is a file that already exists.
    if (behavior.destinationFileExistsSrcIsFileOutcome().getType() ==
        MethodOutcome.Type.RETURNS_TRUE) {
      renameData.add(new RenameData(
          "dst is a file that already exists: 1",
          bucketName, "f1",
          bucketName, "f2",
          behavior.destinationFileExistsSrcIsFileOutcome(),  // expected outcome
          null,  // expected to exist in src
          Lists.newArrayList("f2"),  // expected to exist in dst
          Lists.newArrayList("f1")));  // expected to be deleted
    } else {
      renameData.add(new RenameData(
          "dst is a file that already exists: 1",
          bucketName, "f1",
          bucketName, "f2",
          behavior.destinationFileExistsSrcIsFileOutcome(),  // expected outcome
          Lists.newArrayList("f1"),  // expected to exist in src
          Lists.newArrayList("f2"),  // expected to exist in dst
          null));  // expected to be deleted
    }

    renameData.add(new RenameData(
        "dst is a file that already exists: 2",
        bucketName, "d0/",
        bucketName, "f2",
        behavior.destinationFileExistsSrcIsDirectoryOutcome(),  // expected outcome
        Lists.newArrayList("d0/"),  // expected to exist in src
        Lists.newArrayList("f2"),  // expected to exist in dst
        null));  // expected to be deleted

    // Parent of destination does not exist.
    renameData.add(new RenameData(
        "Parent of destination does not exist: 1",
        bucketName, "f1",
        bucketName, "does-not-exist/f1",
        behavior.nonExistentDestinationFileParentOutcome(),  // expected outcome
        null,  // expected to exist in src
        null,  // expected to exist in dst
        null));  // expected to be deleted

    if (behavior.nonExistentDestinationDirectoryParentOutcome().getType() ==
        MethodOutcome.Type.RETURNS_TRUE) {
      renameData.add(new RenameData(
          "Parent of destination does not exist: 2",
          bucketName, "d0-b/",
          bucketName, "does-not-exist2/d0-b/",
          behavior.nonExistentDestinationDirectoryParentOutcome(),  // expected outcome
          null,  // expected to exist in src
          Lists.newArrayList("does-not-exist2/d0-b/"),  // expected to exist in dst
          Lists.newArrayList("d0-b/")));  // expected to be deleted
    } else {
      renameData.add(new RenameData(
          "Parent of destination does not exist: 2",
          bucketName, "d0-b/",
          bucketName, "does-not-exist2/d0-b/",
          behavior.nonExistentDestinationDirectoryParentOutcome(),  // expected outcome
          Lists.newArrayList("d0-b/"),  // expected to exist in src
          null,  // expected to exist in dst
          null));  // expected to be deleted
    }


    // This test case fails for LocalFileSystem; it clobbers the destination intead.
    // TODO(user): Make the MethodOutcome able to encompass high-level behaviors.
    renameData.add(new RenameData(
        "destination is a dir that exists and non-empty: 2",
        bucketName, "d1-h/",
        bucketName, "td0-a",
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),  // expected outcome
        Lists.newArrayList("td0-a/", "td0-a/d1-h/",
            "td0-a/d1-h/f1"),  // expected to exist in src
        null,  // expected to exist in dst
        Lists.newArrayList("d1-h/", "d1-h/f1")));  // expected to be deleted

    // Rename a dir: destination is a dir that does not exist
    renameData.add(new RenameData(
        "destination is a dir that does not exist",
        bucketName, "d1-b/",
        bucketName, "td0-x/",
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),  // expected outcome
        Lists.newArrayList("td0-x/", "td0-x/f1"),  // expected to exist in src
        null,  // expected to exist in dst
        Lists.newArrayList("d1-b/", "d1-b/f1")));  // expected to be deleted

    // Rename a dir: destination is a file that does not exist
    renameData.add(new RenameData(
        "destination is a file that does not exist",
        bucketName, "d1-c/",
        bucketName, "td0-a/df",
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),  // expected outcome
        Lists.newArrayList("td0-a/", "td0-a/df/", "td0-a/df/f1"),  // expected to exist in src
        null,  // expected to exist in dst
        Lists.newArrayList("d1-c/", "d1-c/f1")));  // expected to be deleted

    // Rename a file: destination is a file that does not exist
    renameData.add(new RenameData(
        "destination is a file that does not exist",
        bucketName, "d1-d/f1",
        bucketName, "td0-a/f1-x",
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),  // expected outcome
        Lists.newArrayList("d1-d/", "td0-a/", "td0-a/f1-x"),  // expected to exist in src
        null,  // expected to exist in dst
        Lists.newArrayList("d1-d/f1")));  // expected to be deleted

    // Rename a file: destination is root.
    if (behavior.renameFileIntoRootOutcome().getType() == MethodOutcome.Type.RETURNS_TRUE) {
      // TODO(user): Refactor the way assertPathsExist so that it can check for existence in
      // root as well.
      renameData.add(new RenameData(
          "file : destination is root",
          bucketName, "d1-i/f1",
          null, null,
          behavior.renameFileIntoRootOutcome(),  // expected outcome
          Lists.newArrayList("d1-i/"),  // expected to exist in src
          null,  // expected to exist in dst
          Lists.newArrayList("d1-i/f1")));  // expected to be deleted
    } else {
      renameData.add(new RenameData(
          "file : destination is root",
          bucketName, "d1-i/f1",
          null, null,
          behavior.renameFileIntoRootOutcome(),  // expected outcome
          Lists.newArrayList("d1-i/", "d1-i/f1"),  // expected to exist in src
          null,  // expected to exist in dst
          null));  // expected to be deleted
    }


    // Rename a file: src is a directory with a multi-level sub-directory.
    renameData.add(new RenameData(
        "src is a directory with a multi-level subdirectory; dst is a directory which exists.",
        bucketName, "n1-src/",
        bucketName, "n1-dst/",
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),
        Lists.newArrayList("n1-dst/", "n1-dst/n1-src/d1/", "n1-dst/n1-src/d1/f1"),
        null,
        Lists.newArrayList("n1-src/", "n1-src/d1/", "n1-src/d1/f1")));

    // Rename a file: src is a directory with a multi-level sub-directory.
    // Similar to the previous case but with more levels involved.
    renameData.add(new RenameData(
        "src is a directory with a multi-level subdirectory; dst is a directory which exists - 2",
        bucketName, "n2-src/",
        bucketName, "n2-dst/",
        new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE),
        Lists.newArrayList("n2-dst/", "n2-dst/f1",
            "n2-dst/n2-src/d0/", "n2-dst/n2-src/f1",
            "n2-dst/n2-src/d1/f1", "n2-dst/n2-src/d2/d21/d211/f1"),
        null,
        Lists.newArrayList("n2-src/", "n2-src/d0/", "n2-src/f1",
            "n2-src/d1/f1", "n2-src/d2/d21/d211/f1")));

    // -------------------------------------------------------
    // Call rename() for each path and verify the expected behavior.
    final ExecutorService threadPool = Executors.newCachedThreadPool();

    try {
      // First do a run-through to check existence of starting files.
      final List<Throwable> errorList = new ArrayList<>();
      final CountDownLatch checkStartCounter = new CountDownLatch(renameData.size());
      for (final RenameData rd : renameData) {
        threadPool.submit(new Runnable() {
          @Override
          public void run() {
            try {
              // Verify that items that we expect to rename are present before the operation.
              assertPathsExist(
                  rd.description, rd.srcBucketName, rd.objectsExpectedToBeDeleted, true);
            } catch (Throwable t) {
              synchronized (errorList) {
                errorList.add(t);
              }
            } finally {
              checkStartCounter.countDown();
            }
          }
        });
      }
      try {
        checkStartCounter.await();
      } catch (InterruptedException ie) {
        throw new IOException("Interrupted while awaiting counter!", ie);
      }
      if (!errorList.isEmpty()) {
        AssertionError error = new AssertionError();
        for (Throwable t : errorList) {
          error.addSuppressed(t);
        }
        throw error;
      }

      // Do a loop to do all the renames.
      final CountDownLatch renameCounter = new CountDownLatch(renameData.size());
      for (final RenameData rd : renameData) {
        threadPool.submit(new Runnable() {
          @Override
          public void run() {
            try {
              URI src = getPath(rd.srcBucketName, rd.srcObjectName);
              URI dst = getPath(rd.dstBucketName, rd.dstObjectName);
              boolean result = false;

              String desc = src.toString() + " -> " + dst.toString();
              try {
                // Perform the rename operation.
                result = rename(src, dst);

                if (result) {
                  Assert.assertEquals(String.format(
                      "Unexpected result for: %s : %s :: expected %s, actually returned true.",
                      desc, rd.description, rd.expectedOutcome.toString()),
                      rd.expectedOutcome.getType(), MethodOutcome.Type.RETURNS_TRUE);
                } else {
                  Assert.assertEquals(String.format(
                      "Unexpected result for: %s : %s :: expected %s, actually returned false.",
                      desc, rd.description, rd.expectedOutcome.toString()),
                      rd.expectedOutcome.getType(), MethodOutcome.Type.RETURNS_FALSE);
                }
              } catch (Exception e) {
                Assert.assertEquals(String.format(
                    "Unexpected result for: %s : %s :: expected %s, actually threw %s.",
                    desc, rd.description, rd.expectedOutcome.toString(),
                    Throwables.getStackTraceAsString(e)),
                    rd.expectedOutcome.getType(), MethodOutcome.Type.THROWS_EXCEPTION);
              }
            } catch (Throwable t) {
              synchronized (errorList) {
                errorList.add(t);
              }
            } finally {
              renameCounter.countDown();
            }
          }
        });
      }
      try {
        renameCounter.await();
      } catch (InterruptedException ie) {
        throw new IOException("Interrupted while awaiting counter!", ie);
      }
      if (!errorList.isEmpty()) {
        AssertionError error = new AssertionError();
        for (Throwable t : errorList) {
          error.addSuppressed(t);
        }
        throw error;
      }

      // Finally, check the existence of final destination files.
      final CountDownLatch checkDestCounter = new CountDownLatch(renameData.size());
      for (final RenameData rd : renameData) {
        threadPool.submit(new Runnable() {
          @Override
          public void run() {
            try {
              URI src = getPath(rd.srcBucketName, rd.srcObjectName);

              // Verify that items that we expect to exist are present.
              assertPathsExist(
                  rd.description, rd.srcBucketName, rd.objectsExpectedToExistSrc, true);
              String dstBucketName;
              if ((rd.dstBucketName == null) && (rd.dstObjectName == null)) {
                // If both bucket and object names are null that means the destination
                // of the rename is root path. In that case, the leaf directory
                // of the source path becomes the destination bucket.
                String srcDirName = GoogleCloudStorageFileSystem.getItemName(src);
                dstBucketName = srcDirName;
              } else {
                dstBucketName = rd.dstBucketName;
              }
              assertPathsExist(rd.description, dstBucketName, rd.objectsExpectedToExistDst, true);

              // Verify that items that we expect to be deleted are not present.
              assertPathsExist(
                  rd.description, rd.srcBucketName, rd.objectsExpectedToBeDeleted, false);
            } catch (Throwable t) {
              synchronized (errorList) {
                errorList.add(t);
              }
            } finally {
              checkDestCounter.countDown();
            }
          }
        });
      }
      try {
        checkDestCounter.await();
      } catch (InterruptedException ie) {
        throw new IOException("Interrupted while awaiting counter!", ie);
      }
      if (!errorList.isEmpty()) {
        AssertionError error = new AssertionError();
        for (Throwable t : errorList) {
          error.addSuppressed(t);
        }
        throw error;
      }
    } finally {
      threadPool.shutdown();
      try {
        if (!threadPool.awaitTermination(10L, TimeUnit.SECONDS)) {
          System.err.println("Failed to awaitTermination! Forcing executor shutdown.");
          threadPool.shutdownNow();
        }
      } catch (InterruptedException ie) {
        throw new IOException("Interrupted while shutting down threadpool!", ie);
      }
    }
  }

  @Test
  public void testRenameWithContentChecking()
      throws IOException {
    // TODO(user): Split out separate test cases, extract a suitable variant of RenameData to
    // follow same pattern of iterating over subcases.
    String[] fileNames = {
        "test-recursive/oldA/B/file2",
        "test-recursive/oldA/file1",
        "test-flat/oldA/aaa",
        "test-flat/oldA/b"
    };

    // Create the objects; their contents will be their own object names as an ASCII string.
    gcsit.clearBucket(bucketName);
    gcsit.createObjectsWithSubdirs(bucketName, fileNames);

    // Check original file existence.
    String testDescRecursive = "Rename of directory with file1 and subdirectory with file2";
    List<String> originalObjects = ImmutableList.of(
        "test-recursive/",
        "test-recursive/oldA/",
        "test-recursive/oldA/B/",
        "test-recursive/oldA/B/file2",
        "test-recursive/oldA/file1",
        "test-flat/oldA/aaa",
        "test-flat/oldA/b");
    assertPathsExist(testDescRecursive, bucketName, originalObjects, true);

    // Check original file content.
    for (String originalName : fileNames) {
      Assert.assertEquals(originalName, readTextFile(bucketName, originalName));
    }

    // Do rename oldA -> newA in test-recursive.
    {
      URI src = getPath(bucketName, "test-recursive/oldA");
      URI dst = getPath(bucketName, "test-recursive/newA");
      Assert.assertTrue(rename(src, dst));
    }

    // Do rename oldA -> newA in test-flat.
    {
      URI src = getPath(bucketName, "test-flat/oldA");
      URI dst = getPath(bucketName, "test-flat/newA");
      Assert.assertTrue(rename(src, dst));
    }

    // Check resulting file existence.
    List<String> resultingObjects = ImmutableList.of(
        "test-recursive/",
        "test-recursive/newA/",
        "test-recursive/newA/B/",
        "test-recursive/newA/B/file2",
        "test-recursive/newA/file1",
        "test-flat/newA/aaa",
        "test-flat/newA/b");
    assertPathsExist(testDescRecursive, bucketName, resultingObjects, true);

    // Check resulting file content.
    for (String originalName : fileNames) {
      String resultingName = originalName.replaceFirst("oldA", "newA");
      Assert.assertEquals(originalName, readTextFile(bucketName, resultingName));
    }

    // Things which mustn't exist anymore.
    List<String> deletedObjects = ImmutableList.of(
        "test-recursive/oldA/",
        "test-recursive/oldA/B/",
        "test-recursive/oldA/B/file2",
        "test-recursive/oldA/file1",
        "test-flat/oldA/aaa",
        "test-flat/oldA/b");
    assertPathsExist(testDescRecursive, bucketName, deletedObjects, false);
  }

  /**
   * Gets a unique path of a non-existent file.
   */
  public static URI getTempFilePath() {
    return getPath(bucketName, getUniqueFileObjectName());
  }

  /**
   * Returns intermediate sub-paths for the given path.
   *
   * for example,
   * gs://foo/bar/zoo => returns: (gs://foo/, gs://foo/bar/)
   *
   * @param path Path to get sub-paths of.
   * @return List of sub-directory paths.
   */
  private static List<URI> getSubDirPaths(URI path) {
    StorageResourceId resourceId = GoogleCloudStorageFileSystem.validatePathAndGetId(path, true);

    List<URI> subDirPaths = new ArrayList<>();
    List<String> subdirs = GoogleCloudStorageFileSystem.getSubDirs(resourceId.getObjectName());
    for (String subdir : subdirs) {
      subDirPaths.add(getPath(resourceId.getBucketName(), subdir));
    }

    return subDirPaths;
  }

  /**
   * If the given paths are expected to exist then asserts that they do,
   * otherwise asserts that they do not exist.
   */
  private void assertPathsExist(
      String testCaseDescription, String bucketName,
      List<String> objectNames, boolean expectedToExist)
      throws IOException {
    if (objectNames != null) {
      for (String object : objectNames) {
        URI path = getPath(bucketName, object);
        String msg = String.format("test-case: %s :: %s: %s",
            testCaseDescription,
            (expectedToExist ?
                "Path expected to exist but not found" :
                "Path expected to not exist but found"),
            path.toString());
        Assert.assertEquals(msg, expectedToExist, exists(path));
      }
    }
  }

  /**
   * Deletes test buckets that are more than 24 hour old.
   */
  private static void deleteOldTestBuckets()
      throws IOException {

    // Get time to compare with.
    Calendar cal = Calendar.getInstance();
    Date today = cal.getTime();
    cal.add(Calendar.HOUR_OF_DAY, -24);
    Date yesterday = cal.getTime();
    log.debug("Current time: %s, deleting buckets older than: %s ", today, yesterday);

    // Iterate over all buckets to find the old ones.
    List<FileInfo> topLevelDirInfos =
        gcsfs.listFileInfo(GoogleCloudStorageFileSystem.GCS_ROOT);
    for (FileInfo dirInfo : topLevelDirInfos) {
      URI dirPath = dirInfo.getPath();
      StorageResourceId resourceId =
          GoogleCloudStorageFileSystem.validatePathAndGetId(dirPath, true);

      if (GoogleCloudStorageIntegrationTest.isTestBucketName(resourceId.getBucketName()) &&
          (dirInfo.getCreationTime() < yesterday.getTime())) {
        log.debug("...deleting : %s", dirInfo);
        try {
          gcsfs.delete(dirPath, true);
          // If we successfully deleted a bucket then sleep for some time so that we do not
          // exceed rate limit for creating/deleting buckets.
          try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(1));
          } catch (InterruptedException ignored) {
            // Ignore InterruptedException and continue to delete next bucket.
          }
        } catch (IOException e) {
          // Log and ignore so that we can delete as many as we can.
          log.error("... error deleting : %s", dirInfo);
          log.error(e);
        }
      } else {
        log.debug("...skipped  : %s", dirInfo);
      }
    }
  }
}
