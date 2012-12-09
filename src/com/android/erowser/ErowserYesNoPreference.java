/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.erowser;

import com.android.internal.preference.YesNoPreference;

import android.content.Context;
import android.util.AttributeSet;

class ErowserYesNoPreference extends YesNoPreference {

    // This is the constructor called by the inflater
    public ErowserYesNoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (positiveResult) {
            setEnabled(false);

            Context context = getContext();
            if (ErowserSettings.PREF_CLEAR_CACHE.equals(getKey())) {
                ErowserSettings.getInstance().clearCache(context);
                ErowserSettings.getInstance().clearDatabases(context);
            } else if (ErowserSettings.PREF_CLEAR_COOKIES.equals(getKey())) {
                ErowserSettings.getInstance().clearCookies(context);
            } else if (ErowserSettings.PREF_CLEAR_HISTORY.equals(getKey())) {
                ErowserSettings.getInstance().clearHistory(context);
            } else if (ErowserSettings.PREF_CLEAR_FORM_DATA.equals(getKey())) {
                ErowserSettings.getInstance().clearFormData(context);
            } else if (ErowserSettings.PREF_CLEAR_PASSWORDS.equals(getKey())) {
                ErowserSettings.getInstance().clearPasswords(context);
            } else if (ErowserSettings.PREF_EXTRAS_RESET_DEFAULTS.equals(
                    getKey())) {
                ErowserSettings.getInstance().resetDefaultPreferences(context);
                setEnabled(true);
            } else if (ErowserSettings.PREF_CLEAR_GEOLOCATION_ACCESS.equals(
                    getKey())) {
                ErowserSettings.getInstance().clearLocationAccess(context);
            }
        }
    }
}
