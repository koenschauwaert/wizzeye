/* Copyright (c) 2018 The Wizzeye Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.wizzeye.app.fragments;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.StringRes;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;

import java.util.Random;
import java.util.regex.Pattern;

import app.wizzeye.app.R;
import app.wizzeye.app.SettingsActivity;
import app.wizzeye.app.service.CallService;

public class RoomSelectionFragment extends BaseFragment implements TextWatcher {

    private static final Pattern ROOM_CHARACTERS = Pattern.compile("^[-_a-z0-9]*$");

    private static final String STATE_ROOM = "room";

    private SharedPreferences mPreferences;
    private String[] mColors;
    private String[] mAnimals;
    private Random mRandom;
    private Button mJoin;
    private EditText mRoom;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        mColors = getResources().getStringArray(R.array.random_colors);
        mAnimals = getResources().getStringArray(R.array.random_animals);
        mRandom = new Random();

        String room = null;
        if (savedInstanceState != null) {
            room = savedInstanceState.getString(STATE_ROOM);
        }
        if (room == null)
            room = mPreferences.getString(SettingsActivity.KEY_LAST_ROOM, null);
        if (room == null)
            room = generateRandomRoom();

        View view = inflater.inflate(R.layout.fragment_room_selection, container, false);
        mJoin = view.findViewById(R.id.join);
        mJoin.setOnClickListener(v -> joinRoom());
        view.findViewById(R.id.random).setOnClickListener(v -> mRoom.setText(generateRandomRoom()));
        mRoom = view.findViewById(R.id.room);
        mRoom.addTextChangedListener(this);
        mRoom.setText(room);
        mRoom.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                joinRoom();
                return true;
            }
            return false;
        });

        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ROOM, mRoom.getText().toString());
    }

    private String generateRandomRoom() {
        return mColors[mRandom.nextInt(mColors.length)] + "-" + mAnimals[mRandom.nextInt(mAnimals.length)];
    }

    private @StringRes int validateRoomName(String name) {
        if (!ROOM_CHARACTERS.matcher(name).matches()) {
            return R.string.roomselection_error_invalid;
        } else if (name.length() < 5) {
            return R.string.roomselection_error_short;
        } else if (name.length() > 64) {
            return R.string.roomselection_error_long;
        } else {
            return 0;
        }
    }

    private void joinRoom() {
        String room = mRoom.getText().toString();
        if (validateRoomName(room) != 0)
            return;
        Uri server = Uri.parse(mPreferences.getString(SettingsActivity.KEY_SERVER, getString(R.string.default_server)));
        mMainActivity.startService(new Intent(mMainActivity, CallService.class)
            .setData(Uri.withAppendedPath(server, Uri.encode(room))));
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        String name = s.toString();

        // Clean up room name
        name = name.toLowerCase();
        name = name.replaceAll("\\s", "-");
        if (!name.equals(s.toString())) {
            s.replace(0, s.length(), name);
            return; // this method will be called again
        }

        // Validate room name
        @StringRes int error = validateRoomName(name);
        mRoom.setError(error != 0 ? getString(error) : null);
        mJoin.setEnabled(error == 0);
    }

}
