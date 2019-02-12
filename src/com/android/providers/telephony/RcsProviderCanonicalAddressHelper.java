package com.android.providers.telephony;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;
import android.provider.Telephony;

public class RcsProviderCanonicalAddressHelper {
    SQLiteOpenHelper mSQLiteOpenHelper;

    RcsProviderCanonicalAddressHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSQLiteOpenHelper = sqLiteOpenHelper;
    }

    Cursor getOrCreateCanonicalAddress(String canonicalAddress) {
        SQLiteDatabase db = mSQLiteOpenHelper.getReadableDatabase();

        Cursor cursor = db.query(
                MmsSmsProvider.TABLE_CANONICAL_ADDRESSES,
                new String[]{BaseColumns._ID}, Telephony.CanonicalAddressesColumns.ADDRESS + "=?",
                new String[]{canonicalAddress}, null, null, null);

        if (cursor != null && cursor.getCount() > 0) {
            return cursor;
        }

        if (cursor != null) {
            cursor.close();
        }

        return insertCanonicalAddress(canonicalAddress);
    }

    private Cursor insertCanonicalAddress(String canonicalAddress) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Telephony.CanonicalAddressesColumns.ADDRESS, canonicalAddress);

        SQLiteDatabase db = mSQLiteOpenHelper.getWritableDatabase();

        long id = db.insert(MmsSmsProvider.TABLE_CANONICAL_ADDRESSES, null, contentValues);

        if (id == -1) {
            return null;
        }

        MatrixCursor matrixCursor = new MatrixCursor(new String[]{BaseColumns._ID}, 1);
        matrixCursor.addRow(new Object[]{id});

        return matrixCursor;
    }
}
