/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.providers.contacts;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog.Calls;
import android.provider.VoicemailContract;
import android.provider.VoicemailContract.Voicemails;
import android.test.MoreAsserts;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link VoicemailContentProvider}.
 *
 * Run the test like this:
 * <code>
 * runtest -c com.android.providers.contacts.VoicemailProviderTest contactsprov
 * </code>
 */
// TODO: Test that calltype and voicemail_uri are auto populated by the provider.
public class VoicemailProviderTest extends BaseContactsProvider2Test {
    private static final String ALL_PERMISSION =
            "com.android.voicemail.permission.READ_WRITE_ALL_VOICEMAIL";
    private static final String OWN_PERMISSION =
            "com.android.voicemail.permission.READ_WRITE_OWN_VOICEMAIL";
    /** Fields specific to call_log provider that should not be exposed by voicemail provider. */
    private static final String[] CALLLOG_PROVIDER_SPECIFIC_COLUMNS = {
            Calls.CACHED_NAME,
            Calls.CACHED_NUMBER_LABEL,
            Calls.CACHED_NUMBER_TYPE,
            Calls.TYPE,
            Calls.VOICEMAIL_URI,
            Calls.COUNTRY_ISO
    };
    /** Total number of columns exposed by voicemail provider. */
    private static final int NUM_VOICEMAIL_FIELDS = 11;

    @Override
    protected Class<? extends ContentProvider> getProviderClass() {
       return TestVoicemailProvider.class;
    }

    @Override
    protected String getAuthority() {
        return VoicemailContract.AUTHORITY;
    }

    private boolean mUseSourceUri = false;
    private File mTestDirectory;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addProvider(TestVoicemailProvider.class, VoicemailContract.AUTHORITY);
        setUpForOwnPermission();
        TestVoicemailProvider.setVvmProviderCallDelegate(createMockProviderCalls());
    }

    @Override
    protected void tearDown() throws Exception {
        removeTestDirectory();
        super.tearDown();
    }

    private void setUpForOwnPermission() {
        // Give away full permission, in case it was granted previously.
        mActor.removePermissions(ALL_PERMISSION);
        mActor.addPermissions(OWN_PERMISSION);
        mUseSourceUri = true;
    }

    private void setUpForFullPermission() {
        mActor.addPermissions(OWN_PERMISSION);
        mActor.addPermissions(ALL_PERMISSION);
        mUseSourceUri = false;
    }

    private void setUpForNoPermission() {
        mActor.removePermissions(OWN_PERMISSION);
        mActor.removePermissions(ALL_PERMISSION);
        mUseSourceUri = true;
    }

    private Uri contentUri() {
        return mUseSourceUri ?
                Voicemails.buildSourceUri(mActor.packageName) : Voicemails.CONTENT_URI;
    }

    public void testInsert() throws Exception {
        ContentValues values = getDefaultVoicemailValues();
        Uri uri = mResolver.insert(contentUri(), values);
        assertStoredValues(uri, values);
        assertSelection(uri, values, Voicemails._ID, ContentUris.parseId(uri));
        assertEquals(1, countFilesInTestDirectory());
    }

    // Test to ensure that media content can be written and read back.
    public void testFileContent() throws Exception {
        Uri uri = insertVoicemail();
        OutputStream out = mResolver.openOutputStream(uri);
        byte[] outBuffer = {0x1, 0x2, 0x3, 0x4};
        out.write(outBuffer);
        out.flush();
        out.close();
        InputStream in = mResolver.openInputStream(uri);
        byte[] inBuffer = new byte[4];
        int numBytesRead = in.read(inBuffer);
        assertEquals(numBytesRead, outBuffer.length);
        MoreAsserts.assertEquals(outBuffer, inBuffer);
        // No more data should be left.
        assertEquals(-1, in.read(inBuffer));
        in.close();
    }

    public void testUpdate() {
        Uri uri = insertVoicemail();
        ContentValues values = new ContentValues();
        values.put(Voicemails.NUMBER, "1-800-263-7643");
        values.put(Voicemails.DATE, 2000);
        values.put(Voicemails.DURATION, 40);
        values.put(Voicemails.STATE, 2);
        values.put(Voicemails.HAS_CONTENT, 1);
        values.put(Voicemails.SOURCE_DATA, "foo");
        int count = mResolver.update(uri, values, null, null);
        assertEquals(1, count);
        assertStoredValues(uri, values);
    }

    public void testDelete() {
        Uri uri = insertVoicemail();
        int count = mResolver.delete(contentUri(), Voicemails._ID + "="
                + ContentUris.parseId(uri), null);
        assertEquals(1, count);
        assertEquals(0, getCount(uri, null, null));
    }

    public void testGetType() throws Exception {
        // voicemail with no MIME type.
        ContentValues values = getDefaultVoicemailValues();
        Uri uri = mResolver.insert(contentUri(), values);
        assertEquals(null, mResolver.getType(uri));

        values.put(Voicemails.MIME_TYPE, "foo/bar");
        uri = mResolver.insert(contentUri(), values);
        assertEquals("foo/bar", mResolver.getType(uri));

        // base URIs.
        assertEquals(Voicemails.DIR_TYPE, mResolver.getType(Voicemails.CONTENT_URI));
        assertEquals(Voicemails.DIR_TYPE, mResolver.getType(Voicemails.buildSourceUri("foo")));
    }

    // Test to ensure that without full permission it is not possible to use the base uri (i.e. with
    // no package URI specified).
    public void testMustUsePackageUriWithoutFullPermission() {
        setUpForOwnPermission();
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.insert(Voicemails.CONTENT_URI, getDefaultVoicemailValues());
            }
        });

        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.update(Voicemails.CONTENT_URI, getDefaultVoicemailValues(), null, null);
            }
        });

        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.query(Voicemails.CONTENT_URI, null, null, null, null);
            }
        });

        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.delete(Voicemails.CONTENT_URI, null, null);
            }
        });
    }

    public void testPermissions_InsertAndQuery() {
        setUpForFullPermission();
        // Insert two records - one each with own and another package.
        insertVoicemail();
        insertVoicemailForSourcePackage("another-package");
        assertEquals(2, getCount(contentUri(), null, null));

        // Now give away full permission and check that only 1 message is accessible.
        setUpForOwnPermission();
        assertEquals(1, getCount(contentUri(), null, null));

        // Once again try to insert message for another package. This time
        // it should fail.
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                insertVoicemailForSourcePackage("another-package");
            }
        });
    }

    public void testPermissions_UpdateAndDelete() {
        setUpForFullPermission();
        // Insert two records - one each with own and another package.
        final Uri ownVoicemail = insertVoicemail();
        final Uri anotherVoicemail = insertVoicemailForSourcePackage("another-package");
        assertEquals(2, getCount(contentUri(), null, null));

        // Now give away full permission and check that we can update and delete only
        // the own voicemail.
        setUpForOwnPermission();
        mResolver.update(withSourcePackageParam(ownVoicemail),
                getDefaultVoicemailValues(), null, null);
        mResolver.delete(withSourcePackageParam(ownVoicemail), null, null);

        // However, attempting to update or delete another-package's voicemail should fail.
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.update(anotherVoicemail, null, null, null);
            }
        });
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.delete(anotherVoicemail, null, null);
            }
        });
    }

    private Uri withSourcePackageParam(Uri uri) {
        return uri.buildUpon()
            .appendQueryParameter(VoicemailContract.PARAM_KEY_SOURCE_PACKAGE, mActor.packageName)
            .build();
    }

    // Test to ensure that all operations fail when no voicemail permission is granted.
    public void testNoPermissions() {
        setUpForNoPermission();
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.insert(contentUri(), getDefaultVoicemailValues());
            }
        });
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.update(contentUri(), getDefaultVoicemailValues(), null, null);
            }
        });
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.query(contentUri(), null, null, null, null);
            }
        });
        EvenMoreAsserts.assertThrows(SecurityException.class, new Runnable() {
            @Override
            public void run() {
                mResolver.delete(contentUri(), null, null);
            }
        });
    }

    // Test to check that none of the call_log provider specific fields are
    // insertable through voicemail provider.
    public void testCannotAccessCallLogSpecificFields_Insert() {
        for (String callLogColumn : CALLLOG_PROVIDER_SPECIFIC_COLUMNS) {
            final ContentValues values = getDefaultVoicemailValues();
            values.put(callLogColumn, "foo");
            EvenMoreAsserts.assertThrows("Column: " + callLogColumn,
                    IllegalArgumentException.class, new Runnable() {
                    @Override
                    public void run() {
                        mResolver.insert(contentUri(), values);
                    }
                });
        }
    }

    // Test to check that none of the call_log provider specific fields are
    // exposed through voicemail provider query.
    public void testCannotAccessCallLogSpecificFields_Query() {
        // Query.
        Cursor cursor = mResolver.query(contentUri(), null, null, null, null);
        List<String> columnNames = Arrays.asList(cursor.getColumnNames());
        assertEquals(NUM_VOICEMAIL_FIELDS, columnNames.size());
        // None of the call_log provider specific columns should be present.
        for (String callLogColumn : CALLLOG_PROVIDER_SPECIFIC_COLUMNS) {
            assertFalse("Unexpected column: '" + callLogColumn + "' returned.",
                    columnNames.contains(callLogColumn));
        }
    }

    // Test to check that none of the call_log provider specific fields are
    // updatable through voicemail provider.
    public void testCannotAccessCallLogSpecificFields_Update() {
        for (String callLogColumn : CALLLOG_PROVIDER_SPECIFIC_COLUMNS) {
            final Uri insertedUri = insertVoicemail();
            final ContentValues values = getDefaultVoicemailValues();
            values.put(callLogColumn, "foo");
            EvenMoreAsserts.assertThrows("Column: " + callLogColumn,
                    IllegalArgumentException.class, new Runnable() {
                    @Override
                    public void run() {
                        mResolver.update(insertedUri, values, null, null);
                    }
                });
        }
    }

    /**
     * Inserts a voicemail record with no source package set. The content provider
     * will detect source package.
     */
    private Uri insertVoicemail() {
        return mResolver.insert(contentUri(), getDefaultVoicemailValues());
    }

    /** Inserts a voicemail record for the specified source package. */
    private Uri insertVoicemailForSourcePackage(String sourcePackage) {
        ContentValues values = getDefaultVoicemailValues();
        values.put(Voicemails.SOURCE_PACKAGE, sourcePackage);
        return mResolver.insert(contentUri(), values);
    }

    private ContentValues getDefaultVoicemailValues() {
        ContentValues values = new ContentValues();
        values.put(Voicemails.NUMBER, "1-800-4664-411");
        values.put(Voicemails.DATE, 1000);
        values.put(Voicemails.DURATION, 30);
        values.put(Voicemails.NEW, 1);
        values.put(Voicemails.HAS_CONTENT, 0);
        values.put(Voicemails.SOURCE_DATA, "1234");
        values.put(Voicemails.STATE, Voicemails.STATE_INBOX);
        return values;
    }

    /** The calls that we need to mock out for our VvmProvider, used by TestVoicemailProvider. */
    public interface VvmProviderCalls {
        public void sendOrderedBroadcast(Intent intent, String receiverPermission);
        public File getDir(String name, int mode);
    }

    public static class TestVoicemailProvider extends VoicemailContentProvider {
        private static VvmProviderCalls mDelgate;

        public static synchronized void setVvmProviderCallDelegate(VvmProviderCalls delegate) {
            mDelgate = delegate;
        }

        @Override
        protected ContactsDatabaseHelper getDatabaseHelper(Context context) {
            return new ContactsDatabaseHelper(context);
        }

        @Override
        protected Context context() {
            return new ContextWrapper(getContext()) {
                @Override
                public File getDir(String name, int mode) {
                    return mDelgate.getDir(name, mode);
                }
                @Override
                public void sendBroadcast(Intent intent, String receiverPermission) {
                    mDelgate.sendOrderedBroadcast(intent, receiverPermission);
                }
            };
        }

        @Override
        protected String getCallingPackage() {
            return getContext().getPackageName();
        }

        @Override
        protected List<ComponentName> getBroadcastReceiverComponents(String intentAction, Uri uri) {
            List<ComponentName> broadcastReceiverComponents = new ArrayList<ComponentName>();
            broadcastReceiverComponents.add(new ComponentName(
                    getContext().getPackageName(), "TestReceiverClass"));
            return broadcastReceiverComponents;
        }
    }

    /** Lazily construct the test directory when required. */
    private synchronized File getTestDirectory() {
        if (mTestDirectory == null) {
            File baseDirectory = getContext().getCacheDir();
            mTestDirectory = new File(baseDirectory, Long.toString(System.currentTimeMillis()));
            assertFalse(mTestDirectory.exists());
            assertTrue(mTestDirectory.mkdirs());
        }
        return mTestDirectory;
    }

    private void removeTestDirectory() {
        if (mTestDirectory != null) {
            recursiveDeleteAll(mTestDirectory);
        }
    }

    private static void recursiveDeleteAll(File input) {
        if (input.isDirectory()) {
            for (File file : input.listFiles()) {
                recursiveDeleteAll(file);
            }
        }
        assertTrue("error deleting " + input.getAbsolutePath(), input.delete());
    }

    private List<File> findAllFiles(File input) {
        if (input == null) {
            return Collections.emptyList();
        }
        if (!input.isDirectory()) {
            return Collections.singletonList(input);
        }
        List<File> results = new ArrayList<File>();
        for (File file : input.listFiles()) {
            results.addAll(findAllFiles(file));
        }
        return results;
    }

    private int countFilesInTestDirectory() {
        return findAllFiles(mTestDirectory).size();
    }

    // TODO: Use a mocking framework to mock these calls.
    private VvmProviderCalls createMockProviderCalls() {
        return new VvmProviderCalls() {
            @Override
            public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
                // Do nothing for now.
            }

            @Override
            public File getDir(String name, int mode) {
                return getTestDirectory();
            }
        };
    }
}