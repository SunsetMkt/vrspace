package org.vrspace.server.api;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;

import javax.servlet.http.HttpSession;

import org.apache.commons.io.FileUtils;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.vrspace.server.config.SeleniumConfig.WebSession;
import org.vrspace.server.config.SeleniumConfig.WebSessionFactory;

import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;

/**
 * Remote browser support. Receives basic commands, forwards them to the
 * headless browser, creates and returns the screenshot. Screenshots are
 * rendered in 2048x1024 resolution, supposedly optimal to be used as textures.
 * 
 * @author joe
 *
 */
@RestController
@Slf4j
@RequestMapping("/webbrowser")
public class SeleniumController {
  @Autowired
  WebSessionFactory factory;

  /**
   * @return true if remote browsing is available
   */
  @GetMapping("/available")
  public boolean available(HttpSession session) {
    return true;
  }

  /**
   * Get a web page
   * 
   * @param url     web page to browse to
   * @param session provided by spring
   * @return screenshot of the rendered page
   */
  @GetMapping(value = "/get", produces = MediaType.IMAGE_PNG_VALUE)
  public @ResponseBody byte[] get(String url, HttpSession session) {
    log.debug("Browser getting " + url);
    WebSession webSession = session(session);
    webSession.webDriver.get(url);
    return screenshot(webSession.webDriver);
  }

  /**
   * Click on a pixel on the screen. This may do nothing or anything, including
   * opening a new tab.
   * 
   * @param x       position from left
   * @param y       position from top
   * @param session provided by spring
   * @return screenshot of the rendered page
   */
  @GetMapping(value = "/click", produces = MediaType.IMAGE_PNG_VALUE)
  @ApiResponses({ @ApiResponse(responseCode = "200", description = "Clicked, returns screenshot", headers = {
      @Header(name = "active-element", description = "Tag of the active element (clicked on)") }) })
  @ResponseBody
  public ResponseEntity<byte[]> click(int x, int y, HttpSession session) {
    log.debug("click:" + x + "," + y);
    WebSession webSession = session(session);
    int numTabs = webSession.activeTabs();

    PointerInput mouse = new PointerInput(PointerInput.Kind.MOUSE, "default mouse");
    Sequence actions = new Sequence(mouse, 0)
        .addAction(mouse.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y))
        .addAction(mouse.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
        .addAction(mouse.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

    ((RemoteWebDriver) webSession.webDriver).perform(Collections.singletonList(actions));

    HttpHeaders headers = new HttpHeaders();

    wait(webSession.webDriver);
    if (numTabs < webSession.activeTabs()) {
      // new tab has opened
      log.debug("new window");
      webSession.switchTab();
    } else {
      // another action in this tab
      webSession.action();
    }

    ResponseEntity<byte[]> ret = new ResponseEntity<>(screenshot(webSession.webDriver), headers, HttpStatus.OK);

    WebElement activeElement = webSession.webDriver.switchTo().activeElement();
    log.debug("Currently active: " + activeElement.getTagName() + " " + activeElement.getAccessibleName());
    headers.add("active-element", activeElement.getTagName());

    return ret;
  }

  /**
   * Scroll up or down by given number of pixels.
   * 
   * @param pixels  positive down, or negative up
   * @param session provided by spring
   * @return screenshot of the page
   */
  @GetMapping(value = "/scroll", produces = MediaType.IMAGE_PNG_VALUE)
  @ResponseBody
  public byte[] scroll(int pixels, HttpSession session) {
    log.debug("Scroll " + pixels);
    WebSession webSession = session(session);

    JavascriptExecutor jse = (JavascriptExecutor) webSession.webDriver;
    jse.executeScript("window.scrollBy(0," + pixels + ")");

    return screenshot(webSession.webDriver);
  }

  /**
   * Close the browser window/tab. Returns to previous tab if any, or returns no
   * content (http 204 status).
   * 
   * @param session provided by spring
   * @return screenshot, may be empty if the browser was closed.
   */
  @GetMapping(value = "/close", produces = MediaType.IMAGE_PNG_VALUE)
  @ResponseBody
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Closed a window and switched to previous one, returns screenshot"),
      @ApiResponse(responseCode = "204", description = "Closed last available window, no content") })
  public ResponseEntity<byte[]> close(HttpSession session) {
    log.debug("Close window");
    WebSession webSession = session(session);

    if (webSession.close() == 0) {
      session.removeAttribute(WebSession.KEY);
      ResponseEntity<byte[]> empty = new ResponseEntity<>(HttpStatus.NO_CONTENT);
      return empty;
    }

    return new ResponseEntity<byte[]>(screenshot(webSession.webDriver), HttpStatus.OK);
  }

  /**
   * Quit current browser
   */
  @GetMapping(value = "/quit")
  public void quit(HttpSession session) {
    WebSession ret = (WebSession) session.getAttribute(WebSession.KEY);
    if (ret != null) {
      ret.webDriver.quit();
      session.removeAttribute(WebSession.KEY);
    }
  }

  /**
   * Navigate back
   */
  @GetMapping(value = "/back", produces = MediaType.IMAGE_PNG_VALUE)
  @ResponseBody
  public byte[] back(HttpSession session) {
    log.debug("back");
    WebSession webSession = session(session);
    if (webSession.back() > 0) {
      log.debug("navigating back");
      // webSession.webDriver.navigate().back(); // apparently this hangs
      ((JavascriptExecutor) webSession.webDriver).executeScript("history.back()");
    } else if (webSession.activeTabs() > 1) {
      // can't go back, close the tab
      log.debug("Last action, closing window");
      webSession.close();
    } else {
      log.debug("last window, last action");
    }
    return screenshot(webSession.webDriver);
  }

  /**
   * Navigate forward
   */
  @GetMapping(value = "/forward", produces = MediaType.IMAGE_PNG_VALUE)
  @ResponseBody
  public byte[] forward(HttpSession session) {
    log.debug("forward");
    WebSession webSession = session(session);
    webSession.action();
    // webSession.webDriver.navigate().forward(); // may hang
    ((JavascriptExecutor) webSession.webDriver).executeScript("history.forward()");
    return screenshot(webSession.webDriver);
  }

  private byte[] screenshot(WebDriver driver) {
    wait(driver);
    log.debug("Browser taking screenshot");
    byte[] ret;
    try {
      File scrFile = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
      ret = FileUtils.readFileToByteArray(scrFile);
    } catch (IOException e) {
      throw new ApiException("Can't read screenshot file: " + e);
    }
    return ret;
  }

  private void wait(WebDriver webDriver) {
    WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
    wait.until(driver -> ((JavascriptExecutor) driver).executeScript("return document.readyState").equals("complete"));
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
    }
  }

  private WebSession session(HttpSession session) {
    WebSession ret = (WebSession) session.getAttribute(WebSession.KEY);
    if (ret == null) {
      ret = factory.newSession();
      session.setAttribute("webDriver", ret);
    }
    return ret;
  }

}
