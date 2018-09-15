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
package app.wizzeye.app;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class LogsActivity extends BaseActivity {

    private LogcatAdapter mAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        mAdapter = new LogcatAdapter();
        ((ListView) findViewById(R.id.logs)).setAdapter(mAdapter);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setTitle(R.string.logs_title);
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.logs, menu);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAdapter.reload();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            finish();
            return true;
        case R.id.share:
            startActivity(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, mAdapter.toString()));
            return true;
        case R.id.refresh:
            mAdapter.reload();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    private static class LogcatAdapter extends BaseAdapter {

        private List<String> mLines = Collections.emptyList();

        void reload() {
            try {
                Process process = Runtime.getRuntime().exec("logcat -d -t 500 -v time");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                mLines = reader.lines().collect(Collectors.toList());
            } catch (IOException e) {
                StringWriter str = new StringWriter();
                e.printStackTrace(new PrintWriter(str));
                mLines = Collections.singletonList(str.toString());
            }
            notifyDataSetChanged();
        }

        @Override
        public String toString() {
            return BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_NAME + "\n" +
                   mLines.stream().collect(Collectors.joining("\n"));
        }

        @Override
        public int getCount() {
            return mLines.size();
        }

        @Override
        public String getItem(int position) {
            return mLines.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_logline, parent, false);
            }
            String line = getItem(position);
            ((TextView) view.findViewById(R.id.text)).setText(line);
            return view;
        }

    }

}
