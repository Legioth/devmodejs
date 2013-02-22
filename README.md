devmodejs
=========

Experiment for running GWT development mode without using a browser plugin. Currently only works with Firefox 18 or newer.

To use:

1. Start the XHR proxy: com.github.legioth.devmode.proxy.XhrProxy
1. Add <script type="text/javascript" src="http://localhost:1234/devmode.js"></script> to your host page
1. Disable the normal dev mode add-on to ensure that it isn't accidentally used.
1. Ensure you don't have the Net panel active in Firebug, because it uses exessive amounts of memory with the thousands of XHR requests that will be sent
1. Modify the devmode support file, depending on what linker you are using
  * The default linker has a hosted.html file. Add ``$wnd.installDevMode(window);`` after the first line of javascript (that defines the ``$wnd`` variable)
  * The xsiframe linker has a modulename.devmode.js file. Add ``$wnd.installDevMode(window);`` after the first statement (that defines the ``$wnd`` variable) in the javascript string in the beginning of the file.
1. Start your normal development mode process. You should also be aware that the Google plugin for Eclipse seems to replace hosted.html/modulename.devmode.js with the default files, causing stuff to break down
1. Use devmode normally, but use port 1234 instead of 9997 with the default configuration 

There are a couple of features of the current dev mode browser plugins that are tricky to implement without some external help.

1. Blocking socket communication
  * Dev mode uses a TPC socket
      * E.g. flash could maybe be used for this purpose, even though that locks out mobile browsers
  * Websocket can't be used directly because blocking communication is needed
      * One potential workaround would be to somehow trick the browser into giving you a separate JS event dispatch thread where the websocket communication is done. The original thread could then busy wait on some shared data storage (e.g. localStorage or cookies) until a response has arrived.
  * Current solution uses cross-site synchronous XHR, causing lots of overhead from passing HTTP back and forth
      * ``com.github.legioth.devmode.proxy.XhrProxy`` is a simple Java application that proxies the HTTP requests to the normal devmode socket format
2. Reading and writing of Java fields from JSNI code
  * Supported using the prototype implementation of Harmony Proxies in Firefox 18
  * Some other solution needed for other browsers
      * The potential field ids for a certain Java object / type could be sent to the browsers, so that Object.defineProperty could be used to initialize triggers for those specific properites.
      * Another option is to rewrite the JSNI to use var ``val = javaObject.get(fieldId)`` instead of ``var val = javaObject[fieldId]`` and corresponding for setting values. Not certain whether this somehow breaks the semantics of the code.
3. JavaScript garbage collection
  * Needs a way of telling the code server that a specific java object is no longer referenced by any JS object.
  * Currently not implemented - memory will leak
  * Could be implemented once weak reference support (e.g. http://wiki.ecmascript.org/doku.php?id=strawman:weak_references) is implemented in browsers
4. There's a known problem with running out of stack space under some circumstances. I don't know whether this is caused by some bug in the code, or if the code should just be restructured to reduce the used stack depth.

Any ideas about what to do about these limitations are highly welcome.

This is in many ways a hack - e.g. the parts of the development mode protocol that handles unexpected problems and shutdowns is not implemented.
