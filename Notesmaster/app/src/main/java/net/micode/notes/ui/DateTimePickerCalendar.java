/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import java.util.Calendar;

import net.micode.notes.R;

import android.content.Context;
import android.text.format.DateFormat;
import android.widget.DatePicker;
import android.widget.FrameLayout;
import android.widget.TimePicker;

/**
 * A custom date+time picker that uses Android's built-in DatePicker
 * (calendar mode) and TimePicker (spinner mode) in a vertical layout.
 * Replaces the old NumberPicker-based DateTimePicker with a proper
 * calendar view for date selection.
 */
public class DateTimePickerCalendar extends FrameLayout {

    private DatePicker mDatePicker;
    private TimePicker mTimePicker;
    private Calendar mDate;

    private boolean mIs24HourView;
    private boolean mIsEnabled = true;
    private boolean mInitialising;

    private OnDateTimeChangedListener mOnDateTimeChangedListener;

    public interface OnDateTimeChangedListener {
        void onDateTimeChanged(DateTimePickerCalendar view, int year, int month,
                int dayOfMonth, int hourOfDay, int minute);
    }

    public DateTimePickerCalendar(Context context) {
        this(context, System.currentTimeMillis());
    }

    public DateTimePickerCalendar(Context context, long date) {
        this(context, date, DateFormat.is24HourFormat(context));
    }

    public DateTimePickerCalendar(Context context, long date, boolean is24HourView) {
        super(context);
        mDate = Calendar.getInstance();
        mInitialising = true;

        inflate(context, R.layout.datetime_picker_new, this);

        mDatePicker = findViewById(R.id.datePicker);
        mTimePicker = findViewById(R.id.timePicker);

        // Configure time picker mode
        mIs24HourView = is24HourView;
        mTimePicker.setIs24HourView(is24HourView);

        // Initialize the internal calendar to the provided date
        mDate.setTimeInMillis(date);

        // Initialize DatePicker with the current date values
        mDatePicker.init(mDate.get(Calendar.YEAR), mDate.get(Calendar.MONTH),
                mDate.get(Calendar.DAY_OF_MONTH), new DatePicker.OnDateChangedListener() {
            @Override
            public void onDateChanged(DatePicker view, int year, int monthOfYear,
                    int dayOfMonth) {
                if (mInitialising) {
                    return;
                }
                mDate.set(Calendar.YEAR, year);
                mDate.set(Calendar.MONTH, monthOfYear);
                mDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                onDateTimeChanged();
            }
        });

        // Initialize TimePicker
        mTimePicker.setHour(mDate.get(Calendar.HOUR_OF_DAY));
        mTimePicker.setMinute(mDate.get(Calendar.MINUTE));
        mTimePicker.setOnTimeChangedListener(new TimePicker.OnTimeChangedListener() {
            @Override
            public void onTimeChanged(TimePicker view, int hourOfDay, int minute) {
                if (mInitialising) {
                    return;
                }
                mDate.set(Calendar.HOUR_OF_DAY, hourOfDay);
                mDate.set(Calendar.MINUTE, minute);
                onDateTimeChanged();
            }
        });

        mInitialising = false;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (mIsEnabled == enabled) {
            return;
        }
        super.setEnabled(enabled);
        mDatePicker.setEnabled(enabled);
        mTimePicker.setEnabled(enabled);
        mIsEnabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Get the current date in millis
     *
     * @return the current date in millis
     */
    public long getCurrentDateInTimeMillis() {
        return mDate.getTimeInMillis();
    }

    /**
     * Set the current date
     *
     * @param date The current date in millis
     */
    public void setCurrentDate(long date) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(date);
        setCurrentDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    /**
     * Set the current date
     *
     * @param year The current year
     * @param month The current month
     * @param dayOfMonth The current dayOfMonth
     * @param hourOfDay The current hourOfDay
     * @param minute The current minute
     */
    public void setCurrentDate(int year, int month,
            int dayOfMonth, int hourOfDay, int minute) {
        setCurrentYear(year);
        setCurrentMonth(month);
        setCurrentDay(dayOfMonth);
        setCurrentHour(hourOfDay);
        setCurrentMinute(minute);
    }

    /**
     * Get current year
     *
     * @return The current year
     */
    public int getCurrentYear() {
        return mDate.get(Calendar.YEAR);
    }

    /**
     * Set current year
     *
     * @param year The current year
     */
    public void setCurrentYear(int year) {
        if (!mInitialising && year == getCurrentYear()) {
            return;
        }
        mDate.set(Calendar.YEAR, year);
        mDatePicker.updateDate(year, getCurrentMonth(), getCurrentDay());
        onDateTimeChanged();
    }

    /**
     * Get current month in the year
     *
     * @return The current month in the year
     */
    public int getCurrentMonth() {
        return mDate.get(Calendar.MONTH);
    }

    /**
     * Set current month in the year
     *
     * @param month The month in the year
     */
    public void setCurrentMonth(int month) {
        if (!mInitialising && month == getCurrentMonth()) {
            return;
        }
        mDate.set(Calendar.MONTH, month);
        mDatePicker.updateDate(getCurrentYear(), month, getCurrentDay());
        onDateTimeChanged();
    }

    /**
     * Get current day of the month
     *
     * @return The day of the month
     */
    public int getCurrentDay() {
        return mDate.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * Set current day of the month
     *
     * @param dayOfMonth The day of the month
     */
    public void setCurrentDay(int dayOfMonth) {
        if (!mInitialising && dayOfMonth == getCurrentDay()) {
            return;
        }
        mDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        mDatePicker.updateDate(getCurrentYear(), getCurrentMonth(), dayOfMonth);
        onDateTimeChanged();
    }

    /**
     * Get current hour in 24 hour mode, in the range (0~23)
     * @return The current hour in 24 hour mode
     */
    public int getCurrentHourOfDay() {
        return mDate.get(Calendar.HOUR_OF_DAY);
    }

    /**
     * Set current hour in 24 hour mode, in the range (0~23)
     *
     * @param hourOfDay
     */
    public void setCurrentHour(int hourOfDay) {
        if (!mInitialising && hourOfDay == getCurrentHourOfDay()) {
            return;
        }
        mDate.set(Calendar.HOUR_OF_DAY, hourOfDay);
        mTimePicker.setHour(hourOfDay);
        onDateTimeChanged();
    }

    /**
     * Get current minute
     *
     * @return The Current Minute
     */
    public int getCurrentMinute() {
        return mDate.get(Calendar.MINUTE);
    }

    /**
     * Set current minute
     */
    public void setCurrentMinute(int minute) {
        if (!mInitialising && minute == getCurrentMinute()) {
            return;
        }
        mDate.set(Calendar.MINUTE, minute);
        mTimePicker.setMinute(minute);
        onDateTimeChanged();
    }

    /**
     * @return true if this is in 24 hour view else false.
     */
    public boolean is24HourView() {
        return mIs24HourView;
    }

    /**
     * Set whether in 24 hour or AM/PM mode.
     *
     * @param is24HourView True for 24 hour mode. False for AM/PM mode.
     */
    public void set24HourView(boolean is24HourView) {
        if (mIs24HourView == is24HourView) {
            return;
        }
        mIs24HourView = is24HourView;
        mTimePicker.setIs24HourView(is24HourView);
        // Re-set the hour to ensure display updates correctly
        mTimePicker.setHour(getCurrentHourOfDay());
    }

    /**
     * Set the callback that indicates the date/time has been changed.
     * @param callback the callback, if null will do nothing
     */
    public void setOnDateTimeChangedListener(OnDateTimeChangedListener callback) {
        mOnDateTimeChangedListener = callback;
    }

    private void onDateTimeChanged() {
        if (mOnDateTimeChangedListener != null) {
            mOnDateTimeChangedListener.onDateTimeChanged(this, getCurrentYear(),
                    getCurrentMonth(), getCurrentDay(), getCurrentHourOfDay(), getCurrentMinute());
        }
    }
}
