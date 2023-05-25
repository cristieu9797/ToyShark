package com.lipisoft.toyshark;

import android.text.TextUtils;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Created by zengzheying on 15/12/30.
 */
public class HttpRequestHeaderParser {
    private static String remoteHost = "";
    private static String requestUrl = "";
    private static String pathUrl = "";
    private static String method = "";
    private static boolean isHttpsSession;
    private static boolean isHttp;
    public static void parseHttpRequestHeader( byte[] buffer, int offset, int count) {
        remoteHost = "";
        requestUrl = "";
        pathUrl = "";
        method = "";
        isHttpsSession = false;
        isHttp = false;
        try {
            Log.e("TAGDEBUG", "parseHttpRequestHeader: bufferLength= " + buffer.length + " offset= " + offset + " count= " + count + " test= " + new String(buffer, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        try {
            switch (buffer[offset]) {
                //GET
                case 'G':
                    //HEAD
                case 'H':
                    //POST, PUT
                case 'P':
                    //DELETE
                case 'D':
                    //OPTIONS
                case 'O':
                    //TRACE
                case 'T':
                    //CONNECT
                case 'C':
                    getHttpHostAndRequestUrl( buffer, offset, count);
                    break;
                //SSL
                case 0x16:
                    remoteHost = getSNI( buffer, offset, count);
                    isHttpsSession = true;
                    break;
                default:
                    break;
            }
        } catch (Exception ex) {
            Log.e("TAGDEBUG","Error: parseHost: "+ ex);
        }
    }

    public static void getHttpHostAndRequestUrl( byte[] buffer, int offset, int count) {
        Log.e("TAGDEBUG","inceput host: " );

        isHttp = true;
        isHttpsSession = false;
        String headerString = new String(buffer, offset, count);    //offset 40 e bun, noi primim 25 acum
        String[] headerLines = headerString.split("\\r\\n");
        Log.e("TAGDEBUG", "getHttpHostAndRequestUrl: " + Arrays.toString(headerLines));
        String host = getHttpHost(headerLines);
        if (!TextUtils.isEmpty(host)) {
            remoteHost = host;
        }
        paresRequestLine(headerLines[0]);

        Log.e("TAGDEBUG","Host: " + remoteHost);
        Log.e("TAGDEBUG","\nRequest: "+ method+ " " + requestUrl);
    }

    public static String getRemoteHost(byte[] buffer, int offset, int count) {
        String headerString = new String(buffer, offset, count);
        String[] headerLines = headerString.split("\\r\\n");
        return getHttpHost(headerLines);
    }

    public static String getHttpHost(String[] headerLines) {
        for (int i = 1; i < headerLines.length; i++) {
            String[] nameValueStrings = headerLines[i].split(":");
            if (nameValueStrings.length == 2 || nameValueStrings.length == 3) {
                String name = nameValueStrings[0].toLowerCase(Locale.ENGLISH).trim();
                String value = nameValueStrings[1].trim();
                if ("host".equals(name)) {
                    return value;
                }
            }
        }
        return null;
    }

    public static void paresRequestLine( String requestLine) {
        String[] parts = requestLine.trim().split(" ");
        if (parts.length == 3) {
            method = parts[0];
            String url = parts[1];
            pathUrl = url;
            if (url.startsWith("/")) {
                if (remoteHost != null) {
                    requestUrl = "http://" + remoteHost + url;
                }
            } else {
                if (requestUrl.startsWith("http")) {
                    requestUrl = url;
                } else {
                    requestUrl = "http://" + url;
                }

            }
        }
    }

    public static String getSNI( byte[] buffer, int offset, int count) {
        int limit = offset + count;
        //TLS Client Hello
        if (count > 43 && buffer[offset] == 0x16) {
            //Skip 43 byte header
            offset += 43;

            //read sessionID
            if (offset + 1 > limit) {
                return null;
            }
            int sessionIDLength = buffer[offset++] & 0xFF;
            offset += sessionIDLength;

            //read cipher suites
            if (offset + 2 > limit) {
                return null;
            }

            int cipherSuitesLength = readShort(buffer, offset) & 0xFFFF;
            offset += 2;
            offset += cipherSuitesLength;

            //read Compression method.
            if (offset + 1 > limit) {
                return null;
            }
            int compressionMethodLength = buffer[offset++] & 0xFF;
            offset += compressionMethodLength;
            if (offset == limit) {
                Log.e("TAGDEBUG","TLS Client Hello packet doesn't contains SNI info.(offset == limit)");
                return null;
            }

            //read Extensions
            if (offset + 2 > limit) {
                return null;
            }
            int extensionsLength = readShort(buffer, offset) & 0xFFFF;
            offset += 2;

            if (offset + extensionsLength > limit) {
                Log.e("TAGDEBUG","TLS Client Hello packet is incomplete.");
                return null;
            }

            while (offset + 4 <= limit) {
                int type0 = buffer[offset++] & 0xFF;
                int type1 = buffer[offset++] & 0xFF;
                int length = readShort(buffer, offset) & 0xFFFF;
                offset += 2;
                //have SNI
                if (type0 == 0x00 && type1 == 0x00 && length > 5) {
                    offset += 5;
                    length -= 5;
                    if (offset + length > limit) {
                        return null;
                    }
                    String serverName = new String(buffer, offset, length);
                    Log.e("TAGDEBUG","SNI: " + serverName +"\n");
                    isHttpsSession = true;
                    return serverName;
                } else {
                    offset += length;
                }

            }
            Log.e("TAGDEBUG","TLS Client Hello packet doesn't contains Host field info.");
            return null;
        } else {
            Log.e("TAGDEBUG","Bad TLS Client Hello packet.");
            return null;
        }
    }

    private static short readShort(byte[] data, int offset) {
        int r = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        return (short) r;
    }

}
