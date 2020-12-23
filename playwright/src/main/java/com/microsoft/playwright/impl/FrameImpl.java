/*
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.playwright.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.microsoft.playwright.Frame.LoadState.*;
import static com.microsoft.playwright.impl.Serialization.*;
import static com.microsoft.playwright.impl.Utils.isFunctionBody;

public class FrameImpl extends ChannelOwner implements Frame {
  private String name;
  private String url;
  FrameImpl parentFrame;
  Set<FrameImpl> childFrames = new LinkedHashSet<>();
  private final Set<LoadState> loadStates = new HashSet<>();
  enum InternalEventType { NAVIGATED, LOADSTATE }
  private final ListenerCollection<InternalEventType> internalListeners = new ListenerCollection<>();
  PageImpl page;
  boolean isDetached;

  FrameImpl(ChannelOwner parent, String type, String guid, JsonObject initializer) {
    super(parent, type, guid, initializer);

    name = initializer.get("name").getAsString();
    url = initializer.get("url").getAsString();
    if (initializer.has("parentFrame")) {
      parentFrame = connection.getExistingObject(initializer.getAsJsonObject("parentFrame").get("guid").getAsString());
      parentFrame.childFrames.add(this);
    }
    for (JsonElement item : initializer.get("loadStates").getAsJsonArray()) {
      loadStates.add(loadStateFromProtocol(item.getAsString()));
    }
  }

  private static LoadState loadStateFromProtocol(String value) {
    switch (value) {
      case "load": return LOAD;
      case "domcontentloaded": return DOMCONTENTLOADED;
      case "networkidle": return NETWORKIDLE;
      default: throw new PlaywrightException("Unexpected value: " + value);
    }
  }

  @Override
  public ElementHandle querySelector(String selector) {
    JsonObject params = new JsonObject();
    params.addProperty("selector", selector);
    JsonElement json = sendMessage("querySelector", params);
    JsonObject element = json.getAsJsonObject().getAsJsonObject("element");
    if (element == null) {
      return null;
    }
    return connection.getExistingObject(element.get("guid").getAsString());
  }

  @Override
  public List<ElementHandle> querySelectorAll(String selector) {
    JsonObject params = new JsonObject();
    params.addProperty("selector", selector);
    JsonElement json = sendMessage("querySelectorAll", params);
    JsonArray elements = json.getAsJsonObject().getAsJsonArray("elements");
    if (elements == null) {
      return null;
    }
    List<ElementHandle> handles = new ArrayList<>();
    for (JsonElement item : elements) {
      handles.add(connection.getExistingObject(item.getAsJsonObject().get("guid").getAsString()));
    }
    return handles;
  }

  @Override
  public Object evalOnSelector(String selector, String pageFunction, Object arg) {
    JsonObject params = new JsonObject();
    params.addProperty("selector", selector);
    params.addProperty("expression", pageFunction);
    params.addProperty("isFunction", isFunctionBody(pageFunction));
    params.add("arg", gson().toJsonTree(serializeArgument(arg)));
    JsonElement json = sendMessage("evalOnSelector", params);
    SerializedValue value = gson().fromJson(json.getAsJsonObject().get("value"), SerializedValue.class);
    return deserialize(value);
  }

  @Override
  public Object evalOnSelectorAll(String selector, String pageFunction, Object arg) {
    JsonObject params = new JsonObject();
    params.addProperty("selector", selector);
    params.addProperty("expression", pageFunction);
    params.addProperty("isFunction", isFunctionBody(pageFunction));
    params.add("arg", gson().toJsonTree(serializeArgument(arg)));
    JsonElement json = sendMessage("evalOnSelectorAll", params);
    SerializedValue value = gson().fromJson(json.getAsJsonObject().get("value"), SerializedValue.class);
    return deserialize(value);
  }

  @Override
  public ElementHandle addScriptTag(AddScriptTagParams params) {
    if (params == null) {
      params = new AddScriptTagParams();
    }
    JsonObject jsonParams = gson().toJsonTree(params).getAsJsonObject();
    if (params.path != null) {
      jsonParams.remove("path");
      byte[] encoded;
      try {
        encoded = Files.readAllBytes(params.path);
      } catch (IOException e) {
        throw new PlaywrightException("Failed to read from file", e);
      }
      String content = new String(encoded, StandardCharsets.UTF_8);
      content += "//# sourceURL=" + params.path.toString().replace("\n", "");
      jsonParams.addProperty("content", content);
    }
    JsonElement json = sendMessage("addScriptTag", jsonParams);
    return connection.getExistingObject(json.getAsJsonObject().getAsJsonObject("element").get("guid").getAsString());
  }

  @Override
  public ElementHandle addStyleTag(AddStyleTagParams params) {
    if (params == null) {
      params = new AddStyleTagParams();
    }
    JsonObject jsonParams = gson().toJsonTree(params).getAsJsonObject();
    if (params.path != null) {
      jsonParams.remove("path");
      byte[] encoded;
      try {
        encoded = Files.readAllBytes(params.path);
      } catch (IOException e) {
        throw new PlaywrightException("Failed to read from file", e);
      }
      String content = new String(encoded, StandardCharsets.UTF_8);
      content += "/*# sourceURL=" + params.path.toString().replace("\n", "") + "*/";
      jsonParams.addProperty("content", content);
    }
    JsonElement json = sendMessage("addStyleTag", jsonParams);
    return connection.getExistingObject(json.getAsJsonObject().getAsJsonObject("element").get("guid").getAsString());
  }

  @Override
  public void check(String selector, CheckOptions options) {
    if (options == null) {
      options = new CheckOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    sendMessage("check", params);
  }

  @Override
  public List<Frame> childFrames() {
    return new ArrayList<>(childFrames);
  }

  @Override
  public void click(String selector, ClickOptions options) {
    if (options == null) {
      options = new ClickOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);

    params.remove("button");
    if (options.button != null) {
      params.addProperty("button", Serialization.toProtocol(options.button));
    }

    params.remove("modifiers");
    if (options.modifiers != null) {
      params.add("modifiers", Serialization.toProtocol(options.modifiers));
    }

    sendMessage("click", params);
  }

  @Override
  public String content() {
    return sendMessage("content").getAsJsonObject().get("value").getAsString();
  }

  @Override
  public void dblclick(String selector, DblclickOptions options) {
    if (options == null) {
      options = new DblclickOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);

    params.remove("button");
    if (options.button != null) {
      params.addProperty("button", Serialization.toProtocol(options.button));
    }

    params.remove("modifiers");
    if (options.modifiers != null) {
      params.add("modifiers", Serialization.toProtocol(options.modifiers));
    }

    sendMessage("dblclick", params);
  }

  @Override
  public void dispatchEvent(String selector, String type, Object eventInit, DispatchEventOptions options) {
    if (options == null) {
      options = new DispatchEventOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    params.addProperty("type", type);
    params.add("eventInit", gson().toJsonTree(serializeArgument(eventInit)));
    sendMessage("dispatchEvent", params);
  }

  @Override
  public Object evaluate(String expression, Object arg) {
    JsonObject params = new JsonObject();
    params.addProperty("expression", expression);
    params.addProperty("world", "main");
    params.addProperty("isFunction", isFunctionBody(expression));
    params.add("arg", gson().toJsonTree(serializeArgument(arg)));
    JsonElement json = sendMessage("evaluateExpression", params);
    SerializedValue value = gson().fromJson(json.getAsJsonObject().get("value"), SerializedValue.class);
    return deserialize(value);
  }

  @Override
  public JSHandle evaluateHandle(String pageFunction, Object arg) {
    JsonObject params = new JsonObject();
    params.addProperty("expression", pageFunction);
    params.addProperty("world", "main");
    params.addProperty("isFunction", isFunctionBody(pageFunction));
    params.add("arg", gson().toJsonTree(serializeArgument(arg)));
    JsonElement json = sendMessage("evaluateExpressionHandle", params);
    return connection.getExistingObject(json.getAsJsonObject().getAsJsonObject("handle").get("guid").getAsString());
  }

  @Override
  public void fill(String selector, String value, FillOptions options) {
    if (options == null) {
      options = new FillOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    params.addProperty("value", value);
    sendMessage("fill", params);
  }

  @Override
  public void focus(String selector, FocusOptions options) {
    if (options == null) {
      options = new FocusOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    sendMessage("focus", params);
  }

  @Override
  public ElementHandle frameElement() {
    JsonObject json = sendMessage("frameElement").getAsJsonObject();
    return connection.getExistingObject(json.getAsJsonObject("element").get("guid").getAsString());
  }

  @Override
  public String getAttribute(String selector, String name, GetAttributeOptions options) {
    if (options == null) {
      options = new GetAttributeOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    params.addProperty("name", name);
    JsonObject json = sendMessage("getAttribute", params).getAsJsonObject();
    if (json.has("value")) {
      return json.get("value").getAsString();
    }
    return null;
  }

  @Override
  public ResponseImpl navigate(String url, NavigateOptions options) {
    if (options == null) {
      options = new NavigateOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("url", url);
    if (options.waitUntil != null) {
      params.remove("waitUntil");
      params.addProperty("waitUntil", toProtocol(options.waitUntil));
    }
    JsonElement result = sendMessage("goto", params);
    JsonObject jsonResponse = result.getAsJsonObject().getAsJsonObject("response");
    if (jsonResponse == null) {
      return null;
    }
    return connection.getExistingObject(jsonResponse.get("guid").getAsString());
  }

  @Override
  public void hover(String selector, HoverOptions options) {
    if (options == null) {
      options = new HoverOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    sendMessage("hover", params);
  }

  @Override
  public String innerHTML(String selector, InnerHTMLOptions options) {
    if (options == null) {
      options = new InnerHTMLOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    JsonObject json = sendMessage("innerHTML", params).getAsJsonObject();
    return json.get("value").getAsString();
  }

  @Override
  public String innerText(String selector, InnerTextOptions options) {
    if (options == null) {
      options = new InnerTextOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    JsonObject json = sendMessage("innerText", params).getAsJsonObject();
    return json.get("value").getAsString();
  }

  @Override
  public boolean isDetached() {
    return isDetached;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Page page() {
    return page;
  }

  @Override
  public Frame parentFrame() {
    return parentFrame;
  }

  @Override
  public void press(String selector, String key, PressOptions options) {
    if (options == null) {
      options = new PressOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    params.addProperty("key", key);
    sendMessage("press", params);
  }

  @Override
  public List<String> selectOption(String selector, ElementHandle.SelectOption[] values, SelectOptionOptions options) {
    if (options == null) {
      options = new SelectOptionOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    if (values != null) {
      params.add("options", gson().toJsonTree(values));
    }
    return selectOption(params);
  }

  @Override
  public List<String> selectOption(String selector, ElementHandle[] values, SelectOptionOptions options) {
    if (options == null) {
      options = new SelectOptionOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    if (values != null) {
      params.add("elements", Serialization.toProtocol(values));
    }
    return selectOption(params);
  }

  private List<String> selectOption(JsonObject params) {
    JsonObject json = sendMessage("selectOption", params).getAsJsonObject();
    return parseStringList(json.getAsJsonArray("values"));
  }

  static String toProtocol(LoadState waitUntil) {
    if (waitUntil == null) {
      waitUntil = LoadState.LOAD;
    }
    switch (waitUntil) {
      case DOMCONTENTLOADED: return "domcontentloaded";
      case LOAD: return "load";
      case NETWORKIDLE: return "networkidle";
      default: throw new PlaywrightException("Unexpected value: " + waitUntil);
    }
  }

  @Override
  public void setContent(String html, SetContentOptions options) {
    if (options == null) {
      options = new SetContentOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("html", html);
    params.remove("waitUntil");
    params.addProperty("waitUntil", toProtocol(options.waitUntil));
    sendMessage("setContent", params);
  }

  @Override
  public void setInputFiles(String selector, Path[] files, SetInputFilesOptions options) {
    setInputFiles(selector, Utils.toFilePayloads(files), options);
  }

  @Override
  public void setInputFiles(String selector, FileChooser.FilePayload[] files, SetInputFilesOptions options) {
    if (options == null) {
      options = new SetInputFilesOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    params.add("files", toJsonArray(files));
    sendMessage("setInputFiles", params);
  }

  @Override
  public void tap(String selector, TapOptions options) {
    if (options == null) {
      options = new TapOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.remove("modifiers");
    if (options.modifiers != null) {
      params.add("modifiers", Serialization.toProtocol(options.modifiers));
    }
    params.addProperty("selector", selector);
    sendMessage("tap", params);
  }

  @Override
  public String textContent(String selector, TextContentOptions options) {
    if (options == null) {
      options = new TextContentOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    return sendMessage("textContent", params).getAsJsonObject().get("value").getAsString();
  }

  @Override
  public String title() {
    JsonElement json = sendMessage("title");
    return json.getAsJsonObject().get("value").getAsString();
  }

  @Override
  public void type(String selector, String text, TypeOptions options) {
    if (options == null) {
      options = new TypeOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    params.addProperty("text", text);
    sendMessage("type", params);
  }

  @Override
  public void uncheck(String selector, UncheckOptions options) {
    if (options == null) {
      options = new UncheckOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    sendMessage("uncheck", params);
  }

  @Override
  public String url() {
    return url;
  }

  @Override
  public Deferred<JSHandle> waitForFunction(String pageFunction, Object arg, WaitForFunctionOptions options) {
    if (options == null) {
      options = new WaitForFunctionOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("expression", pageFunction);
    params.addProperty("isFunction", isFunctionBody(pageFunction));
    params.add("arg", gson().toJsonTree(serializeArgument(arg)));
    Waitable<JSHandle> handle = sendMessageAsync("waitForFunction", params).apply(json -> {
      JsonObject element = json.getAsJsonObject().getAsJsonObject("handle");
      return connection.getExistingObject(element.get("guid").getAsString());
    });
    return toDeferred(handle);
  }

  @Override
  public Deferred<Void> waitForLoadState(LoadState state, WaitForLoadStateOptions options) {
    if (options == null) {
      options = new WaitForLoadStateOptions();
    }
    if (state == null) {
      state = LOAD;
    }

    List<Waitable<Void>> waitables = new ArrayList<>();
    waitables.add(new WaitForLoadStateHelper(state));
    waitables.add(page.createWaitForCloseHelper());
    waitables.add(page.createWaitableTimeout(options.timeout));
    return toDeferred(new WaitableRace<>(waitables));
  }

  private class WaitForLoadStateHelper implements Waitable<Void>, Listener<InternalEventType> {
    private final LoadState expectedState;
    private boolean isDone;

    WaitForLoadStateHelper(LoadState state) {
      expectedState = state;
      isDone = loadStates.contains(state);
      if (!isDone) {
        internalListeners.add(InternalEventType.LOADSTATE, this);
      }
    }

    @Override
    public void handle(Event<InternalEventType> event) {
      assert event.type() == InternalEventType.LOADSTATE;
      if (expectedState.equals(event.data())) {
        isDone = true;
        dispose();
      }
    }

    public void dispose() {
      internalListeners.remove(InternalEventType.LOADSTATE, this);
    }

    public boolean isDone() {
      return isDone;
    }

    @Override
    public Void get() {
      return null;
    }
  }

  private class WaitForNavigationHelper implements Waitable<Response>, Listener<InternalEventType> {
    private final UrlMatcher matcher;
    private final LoadState expectedLoadState;
    private WaitForLoadStateHelper loadStateHelper;

    private RequestImpl request;
    private RuntimeException exception;

    WaitForNavigationHelper(UrlMatcher matcher, LoadState expectedLoadState) {
      this.matcher = matcher;
      this.expectedLoadState = expectedLoadState;
      internalListeners.add(InternalEventType.NAVIGATED, this);
    }

    @Override
    public void handle(Event<InternalEventType> event) {
      assert InternalEventType.NAVIGATED == event.type();
      JsonObject params = (JsonObject) event.data();
      if (!matcher.test(params.get("url").getAsString())) {
        return;
      }
      if (params.has("error")) {
        exception = new PlaywrightException(params.get("error").getAsString());
      } else {
        if (params.has("newDocument")) {
          JsonObject jsonReq = params.getAsJsonObject("newDocument").getAsJsonObject("request");
          if (jsonReq != null) {
            request = connection.getExistingObject(jsonReq.get("guid").getAsString());
          }
        }
        loadStateHelper = new WaitForLoadStateHelper(expectedLoadState);
      }
      internalListeners.remove(InternalEventType.NAVIGATED, this);
    }

    @Override
    public void dispose() {
      internalListeners.remove(InternalEventType.NAVIGATED, this);
      if (loadStateHelper != null) {
        loadStateHelper.dispose();
      }
    }

    @Override
    public boolean isDone() {
      if (exception != null) {
        return true;
      }
      if (loadStateHelper != null) {
        return loadStateHelper.isDone();
      }
      return false;
    }

    @Override
    public Response get() {
      if (exception != null) {
        throw exception;
      }

      if (request == null) {
        return null;
      }
      return request.finalRequest().response();
    }
  }

  @Override
  public Deferred<Response> waitForNavigation(WaitForNavigationOptions options) {
    if (options == null) {
      options = new WaitForNavigationOptions();
    }
    if (options.waitUntil == null) {
      options.waitUntil = LOAD;
    }

    List<Waitable<Response>> waitables = new ArrayList<>();
    UrlMatcher matcher = UrlMatcher.forOneOf(options.glob, options.pattern, options.predicate);
    waitables.add(new WaitForNavigationHelper(matcher, options.waitUntil));
    waitables.add(page.createWaitForCloseHelper());
    waitables.add(page.createWaitableFrameDetach(this));
    waitables.add(page.createWaitableNavigationTimeout(options.timeout));
    return toDeferred(new WaitableRace<>(waitables));
  }

  private static String toProtocol(WaitForSelectorOptions.State state) {
    return state.toString().toLowerCase();
  }

  @Override
  public Deferred<ElementHandle> waitForSelector(String selector, WaitForSelectorOptions options) {
    if (options == null) {
      options = new WaitForSelectorOptions();
    }
    JsonObject params = gson().toJsonTree(options).getAsJsonObject();
    params.addProperty("selector", selector);
    if (options.state != null) {
      params.remove("state");
      params.addProperty("state", toProtocol(options.state));
    }
    Waitable<ElementHandle> handle = sendMessageAsync("waitForSelector", params).apply(json -> {
      JsonObject element = json.getAsJsonObject().getAsJsonObject("element");
      if (element == null) {
        return null;
      }
      return connection.getExistingObject(element.get("guid").getAsString());
    });
    return toDeferred(handle);
  }

  @Override
  public Deferred<Void> waitForTimeout(int timeout) {
    return toDeferred(new WaitableTimeout<Void>(timeout) {
      @Override
      public Void get() {
        // Override to not throw.
        return null;
      }
    });
  }

  protected void handleEvent(String event, JsonObject params) {
    if ("loadstate".equals(event)) {
      JsonElement add = params.get("add");
      if (add != null) {
        LoadState state = loadStateFromProtocol(add.getAsString());
        loadStates.add(state);
        internalListeners.notify(InternalEventType.LOADSTATE, state);
      }
      JsonElement remove = params.get("remove");
      if (remove != null) {
        loadStates.remove(loadStateFromProtocol(remove.getAsString()));
      }
    } else if ("navigated".equals(event)) {
      url = params.get("url").getAsString();
      name = params.get("name").getAsString();
      if (!params.has("error") && page != null) {
        page.frameNavigated(this);
      }
      internalListeners.notify(InternalEventType.NAVIGATED, params);
    }
  }
}
