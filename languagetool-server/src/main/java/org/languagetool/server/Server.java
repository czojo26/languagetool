/* LanguageTool, a natural language style checker
 * Copyright (C) 2012 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.server;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.Nullable;
import org.languagetool.JLanguageTool;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.languagetool.server.HTTPServerConfig.DEFAULT_PORT;

/**
 * Super class for HTTP and HTTPS server.
 *
 * @since 2.0
 */
abstract class Server {

  protected abstract String getProtocol();

  protected static final Set<String> DEFAULT_ALLOWED_IPS = new HashSet<>(Arrays.asList(
          "0:0:0:0:0:0:0:1",     // Suse Linux IPv6 stuff
          "0:0:0:0:0:0:0:1%0",   // some(?) Mac OS X
          "127.0.0.1"
  ));

  protected int port;
  protected String host;
  protected HttpServer server;
  protected LanguageToolHttpHandler httpHandler;

  private boolean isRunning;

  /**
   * Start the server.
   */
  public void run() {
    String hostName = host != null ? host : "localhost";
    System.out.println("Starting LanguageTool " + JLanguageTool.VERSION +
            " (build date: " + JLanguageTool.BUILD_DATE + ") server on " + getProtocol() + "://" + hostName + ":" + port  + "...");
    server.start();
    isRunning = true;
    System.out.println("Server started");
  }

  /**
   * Stop the server. Once stopped, a server cannot be used again.
   */
  public void stop() {
    if (httpHandler != null) {
      httpHandler.shutdown();
    }
    if (server != null) {
      System.out.println("Stopping server");
      server.stop(0);
      isRunning = false;
      System.out.println("Server stopped");
    }
  }

  /**
   * @return whether the server is running
   * @since 2.0
   */
  public boolean isRunning() {
    return isRunning;
  }

  @Nullable
  protected RequestLimiter getRequestLimiterOrNull(HTTPServerConfig config) {
    int requestLimit = config.getRequestLimit();
    int requestLimitPeriodInSeconds = config.getRequestLimitPeriodInSeconds();
    if (requestLimit > 0 || requestLimitPeriodInSeconds > 0) {
      return new RequestLimiter(requestLimit, requestLimitPeriodInSeconds);
    }
    return null;
  }

  protected static boolean usageRequested(String[] args) {
    return args.length == 1 && (args[0].equals("-h") || args[0].equals("--help"));
  }

  protected static void printCommonConfigFileOptions() {
    System.out.println("                 'mode' - 'LanguageTool' or 'AfterTheDeadline' for emulation of After the Deadline output (optional, experimental)");
    System.out.println("                 'afterTheDeadlineLanguage' - language code like 'en' or 'en-GB' (required if mode is 'AfterTheDeadline')");
    System.out.println("                 'maxTextLength' - maximum text length, longer texts will cause an error (optional)");
    System.out.println("                 'maxCheckTimeMillis' - maximum time in milliseconds allowed per check (optional)");
    System.out.println("                 'maxCheckThreads' - maximum number of threads working in parallel (optional)");
    System.out.println("                 'requestLimit' - maximum number of requests (optional)");
    System.out.println("                 'requestLimitPeriodInSeconds' - time period to which requestLimit applies (optional)");
    System.out.println("                 'languageModel' - a directory with '1grams', '2grams', '3grams' sub directories which contain a Lucene index");
    System.out.println("                  each with ngram occurrence counts; activates the confusion rule if supported (optional)");
    System.out.println("                 'maxWorkQueueSize' - reject request if request queue gets larger than this (optional)");
    System.out.println("                 'rulesFile' - a file containing rules configuration, such as .langugagetool.cfg (optional)");
  }

  protected static void printCommonOptions() {
    System.out.println("  --port, -p PRT port to bind to, defaults to " + DEFAULT_PORT + " if not specified");
    System.out.println("  --public       allow this server process to be connected from anywhere; if not set,");
    System.out.println("                 it can only be connected from the computer it was started on");
    System.out.println("  --allow-origin ORIGIN  set the Access-Control-Allow-Origin header in the HTTP response,");
    System.out.println("                         used for direct (non-proxy) JavaScript-based access from browsers;");
    System.out.println("                         example: --allow-origin \"*\"");
    System.out.println("  --verbose, -v  in case of exceptions, log the input text (up to 500 characters)");
    System.out.println("  --languageModel, a directory with '1grams', '2grams', '3grams' sub directories (per language) which contain a Lucene index (optional)");
  }

  protected static void checkForNonRootUser() {
    if ("root".equals(System.getProperty("user.name"))) {
      System.out.println("****************************************************************************************************");
      System.out.println("*** WARNING: this process is running as root - please do not run it as root for security reasons ***");
      System.out.println("****************************************************************************************************");
    }
  }

  protected ThreadPoolExecutor getExecutorService(LinkedBlockingQueue<Runnable> workQueue, HTTPServerConfig config) {
    int threadPoolSize = config.getMaxCheckThreads();
    System.out.println("Setting up thread pool with " + threadPoolSize + " threads");
    return new StoppingThreadPoolExecutor(threadPoolSize, workQueue);
  }

  static class StoppingThreadPoolExecutor extends ThreadPoolExecutor {

    StoppingThreadPoolExecutor(int threadPoolSize, LinkedBlockingQueue<Runnable> workQueue) {
      super(threadPoolSize, threadPoolSize, 0L, TimeUnit.MILLISECONDS, workQueue);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
      super.afterExecute(r, t);
      if (t != null && t instanceof OutOfMemoryError) {
        // we prefer to stop instead of being in an unstable state:
        //noinspection CallToPrintStackTrace
        t.printStackTrace();
        System.exit(1);
      }
    }
  }

}
