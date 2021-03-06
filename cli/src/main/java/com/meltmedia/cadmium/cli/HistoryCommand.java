/**
 *    Copyright 2012 meltmedia
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.meltmedia.cadmium.cli;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.util.EntityUtils;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.meltmedia.cadmium.core.history.HistoryEntry;

/**
 * Retrieves and displays the history of the requested Cadmium site in a human readable format.
 * 
 * @author John McEntire
 *
 */
@Parameters(commandDescription = "Lists history for a given site in human readable form.", separators="=")
public class HistoryCommand extends AbstractAuthorizedOnly implements CliCommand {
  private static final Pattern URI_PATTERN = Pattern.compile("^(http[s]{0,1}://.*)$");

  @Parameter(names="-n", description="Limits number of history items returned.", required=false)
  private Integer limit = -1;
  
  @Parameter(names="-r", description="Filters out non revertible history items from list.", required=false)
  private boolean filter = false;
  
  @Parameter(description="Site", required=true)
  private List<String> site;
  
  public void execute() throws Exception {
    String siteUri = getSecureBaseUrl(site.get(0));
    Matcher siteMatcher = URI_PATTERN.matcher(siteUri);
    if(siteMatcher.matches()) {
      siteUri = siteMatcher.group(1);
      
      System.out.println("Showing history for "+siteUri+":");
      
      List<HistoryEntry> history = getHistory(siteUri, limit, filter, token);
            
      displayHistory(history, false, null);
    } else {
      System.err.println("Invalid value for site parameter!");
      System.exit(1);
    }
  }

  /**
   * Displays a list of {@link HistoryEntry} objects.
   * @param history The list of entries to display.
   * @param filter If true then only revertable history entries will be displayed.
   * @param limitHistory If set this will limit the number of history entries to display.
   */
  public static void displayHistory(List<HistoryEntry> history, boolean filter, Integer limitHistory) {
    if(history != null && history.size() > 0) {
      System.out.format("%7s|%7s|%12s|%7s|%14s|%52s|%18s|%42s|%24s|%6s|%6s|%6s|%6s\n", "Index", "Type", "Date", "Time", "User", "Repository", "Branch", "Revision", "Time Live", "Maint", "Revert", "Done", "Fail");
      for(int i=0; i<218; i++) {
        System.out.print("-");
      }
      System.out.println();
      boolean showing = false;
      for(HistoryEntry entry : history) {
        if(!filter || entry.isRevertible()) {
          if(limitHistory == null || limitHistory-- > 0) {
            showing = true;
            System.out.format("%7d|%7s|%4tm/%<2td/%<4tY|%<4tH:%<2tM|%14s|%52s|%18s|%42s|%24s|%6b|%6b|%6s|%6s\n",
                entry.getIndex(),
                entry.getType(),
                entry.getTimestamp(),
                entry.getOpenId(),
                entry.getRepoUrl(),
                entry.getBranch(),
                entry.getRevision(),
                formatTimeLive(entry.getTimeLive() == 0 ? System.currentTimeMillis() - entry.getTimestamp().getTime() : entry.getTimeLive()),
                entry.isMaintenance(),
                entry.isRevertible(),
                entry.isFinished(),
                entry.isFailed());
            printComments(entry.getComment());
          }
        }
      }
      if(!showing) {
        System.out.println("No history to show");
      }
    } else {
      System.out.println("No history to show");
    }
  }
  
  /**
   * Waits until a timeout is reached or a token shows up in the history of a site as finished or failed.
   * 
   * @param siteUri The uri to a cadmium site.
   * @param token The token that represents a history event to wait for.
   * @param since A timestamp to pass on the the cadmium site to set a limit on how far back to check the history for a token.
   * @param timeout The timeout in milliseconds to wait for if the token never shows up or fails in the sites log.
   * @throws Exception
   */
  public static void waitForToken(String siteUri, String token, Long since, Long timeout) throws Exception {

    if(!siteUri.endsWith("/system/history")) {
      siteUri += "/system/history";
    }
    siteUri += "/" + token;
    if(since != null) {
      siteUri += "/" + since;
    }
    
    HttpClient httpClient = httpClient();
    HttpGet get = new HttpGet(siteUri);
    Long currentTime = System.currentTimeMillis();
    Long timeoutTime = currentTime + timeout;
    do {
      currentTime = System.currentTimeMillis();
      
      HttpResponse resp = httpClient.execute(get);
      if(resp.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
        String response = EntityUtils.toString(resp.getEntity());
        if(response != null && response.trim().equalsIgnoreCase("true")) {
          return;
        } else {
          Thread.sleep(1000l);
        }
      } else {
        String errorResponse = EntityUtils.toString(resp.getEntity());
        if(errorResponse != null) {
          throw new Exception(errorResponse.trim());
        } else {
          throw new Exception("Command failed!");
        }
      }
    } while(currentTime < timeoutTime);
    if(currentTime >= timeoutTime) {
      throw new Exception("Timed out waiting for command to complete!");
    }
  }

  /**
   * Retrieves the history of a Cadmium site.
   * 
   * @param siteUri The uri of a cadmium site.
   * @param limit The maximum number of history entries to retrieve or if set to -1 tells the site to retrieve all history.
   * @param filter If true filters out the non revertable history entries.
   * @param token The Github API token to pass to the Cadmium site for authentication.
   * 
   * @return A list of {@link HistoryEntry} Objects that are populated with the history returned from the Cadmium site.
   * 
   * @throws URISyntaxException
   * @throws IOException
   * @throws ClientProtocolException
   * @throws Exception
   */
  public static List<HistoryEntry> getHistory(String siteUri, int limit, boolean filter, String token)
      throws URISyntaxException, IOException, ClientProtocolException, Exception {

    if(!siteUri.endsWith("/system/history")) {
      siteUri += "/system/history";
    }
    
    List<HistoryEntry> history = null;
    
    HttpClient httpClient = httpClient();
    HttpGet get = null;
    try {
      URIBuilder uriBuilder = new URIBuilder(siteUri);
      if(limit > 0) {
        uriBuilder.addParameter("limit", limit+"");
      }
      if(filter) {
        uriBuilder.addParameter("filter", filter+"");
      }
      URI uri = uriBuilder.build();
      get = new HttpGet(uri);
      addAuthHeader(token, get);
      
      HttpResponse resp = httpClient.execute(get);
      if(resp.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
        HttpEntity entity = resp.getEntity();
        if(entity.getContentType().getValue().equals("application/json")) {
          String responseContent = EntityUtils.toString(entity);
          Gson gson = new GsonBuilder().registerTypeAdapter(Date.class, new JsonDeserializer<Date>() {

            @Override
            public Date deserialize(JsonElement json, Type typeOfT,
                JsonDeserializationContext ctx) throws JsonParseException {
              return new Date(json.getAsLong());
            }
            
          }).create();
          history = gson.fromJson(responseContent, new TypeToken<List<HistoryEntry>>() {}.getType());
        } else {
          System.err.println("Invalid response content type ["+entity.getContentType().getValue()+"]");
          System.exit(1);
        }
      } else {
        System.err.println("Request failed due to a ["+resp.getStatusLine().getStatusCode()+":"+resp.getStatusLine().getReasonPhrase()+"] response from the remote server.");
        System.exit(1);
      }
    } finally {
      if(get != null) {
        get.releaseConnection();
      }
    }
    return history;
  }

  /**
   * Helper method to format comments to standard out.
   * 
   * @param comment
   */
  private static void printComments(String comment) {
    int index = 0;
    int nextIndex = 154;
    while(index < comment.length()) {
      nextIndex = nextIndex <= comment.length() ? nextIndex : comment.length();
      String commentSegment = comment.substring(index, nextIndex);
      int lastSpace = commentSegment.lastIndexOf(' ');
      int lastNewLine = commentSegment.indexOf('\n');
      char lastChar = ' ';
      if(nextIndex < comment.length() ) {
        lastChar = comment.charAt(nextIndex);
      }
      if(lastNewLine > 0) {
        nextIndex = index + lastNewLine;
        commentSegment = comment.substring(index, nextIndex);
      } else
      if(Character.isWhitespace(lastChar)) {
        
      } else
      if(lastSpace > 0) {
        nextIndex = index + lastSpace;
        commentSegment = comment.substring(index, nextIndex);
      }
      System.out.println("  " + commentSegment);
      index = nextIndex;
      if(lastNewLine > 0 || lastSpace > 0) {
        index++;
      }
      nextIndex = index + 154;
    }
  }

  /**
   * Helper method to format a timestamp.
   * 
   * @param timeLive
   * @return
   */
  private static String formatTimeLive(long timeLive) {
    String timeString = "ms";
    timeString = (timeLive % 1000) + timeString;
    timeLive = timeLive / 1000;
    if(timeLive > 0) {
      timeString = (timeLive % 60) + "s" + timeString;
      timeLive = timeLive / 60;
      if(timeLive > 0) {
        timeString = (timeLive % 60) + "m" + timeString;
        timeLive = timeLive / 60;
        if(timeLive > 0) {
          timeString = (timeLive % 24) + "h" + timeString;
          timeLive = timeLive / 24;
          if(timeLive > 0) {
            timeString = (timeLive) + "d" + timeString;
          }
        }
      }
    }
    return timeString;
  }

  @Override
  public String getCommandName() {
    return "history";
  }
  
}
