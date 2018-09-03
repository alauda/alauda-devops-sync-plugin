package io.alauda.jenkins.devops.sync.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public abstract class TimeUtils {
    private TimeUtils(){}

    public static Date getUTCTime() throws ParseException {
        StringBuilder UTCTimeBuffer = new StringBuilder();
        Calendar cal = Calendar.getInstance() ;
        int zoneOffset = cal.get(java.util.Calendar.ZONE_OFFSET);
        int dstOffset = cal.get(java.util.Calendar.DST_OFFSET);
        cal.add(java.util.Calendar.MILLISECOND, -(zoneOffset + dstOffset));
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH)+1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        UTCTimeBuffer.append(year).append("-").append(month).append("-").append(day) ;
        UTCTimeBuffer.append(" ").append(hour).append(":").append(minute) ;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(UTCTimeBuffer.toString()) ;
    }
}
