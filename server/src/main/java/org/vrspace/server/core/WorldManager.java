package org.vrspace.server.core;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.neo4j.core.mapping.Neo4jMappingContext;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.vrspace.server.config.ServerConfig;
import org.vrspace.server.config.WorldConfig;
import org.vrspace.server.config.WorldConfig.WorldProperties;
import org.vrspace.server.dto.ClientRequest;
import org.vrspace.server.dto.Command;
import org.vrspace.server.dto.SceneProperties;
import org.vrspace.server.dto.VREvent;
import org.vrspace.server.dto.Welcome;
import org.vrspace.server.obj.Client;
import org.vrspace.server.obj.Entity;
import org.vrspace.server.obj.Ownership;
import org.vrspace.server.obj.Point;
import org.vrspace.server.obj.RemoteServer;
import org.vrspace.server.obj.User;
import org.vrspace.server.obj.VRObject;
import org.vrspace.server.obj.World;
import org.vrspace.server.types.ID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.nimbusds.oauth2.sdk.util.StringUtils;

import io.openvidu.java.client.OpenViduException;
import lombok.extern.slf4j.Slf4j;

/**
 * Main component that manages all interactions with virtual worlds.
 * 
 * @author joe
 *
 */
@Component("world")
@Slf4j
public class WorldManager {
  @Autowired
  protected ServerConfig config; // used in tests

  @Autowired
  private VRObjectRepository db;

  @Autowired
  protected SceneProperties sceneProperties; // used in tests

  @Autowired
  protected ObjectMapper jackson;

  private ObjectMapper privateJackson;

  @Autowired
  private StreamManager streamManager;

  @Autowired
  protected ClientFactory<User> userFactory; // used in tests
  @Autowired
  protected ClientFactory<RemoteServer> serverFactory;

  @Autowired
  private Neo4jMappingContext mappingContext;

  @Autowired
  private WorldConfig worldConfig;

  private Dispatcher dispatcher;

  protected SessionTracker sessionTracker;

  // used in tests
  protected ConcurrentHashMap<ID, VRObject> cache = new ConcurrentHashMap<ID, VRObject>();

  protected VRObject get(ID id) {
    return cache.get(id);
  }

  private World defaultWorld;

  @SuppressWarnings("rawtypes")
  private Map<Class, PersistenceManager> persistors = new HashMap<>();

  @PostConstruct
  public void init() {
    this.privateJackson = this.jackson.copy();
    this.privateJackson.setAnnotationIntrospector(new JacksonAnnotationIntrospector());
    this.dispatcher = new Dispatcher(this.privateJackson);
    this.sessionTracker = new SessionTracker(this.config);
    for (Class<?> c : ClassUtil.findSubclasses(PersistenceManager.class)) {
      for (Type t : ((ParameterizedType) c.getGenericSuperclass()).getActualTypeArguments()) {
        try {
          @SuppressWarnings("rawtypes")
          PersistenceManager p = (PersistenceManager) c.getConstructor(VRObjectRepository.class).newInstance(db);
          persistors.put((Class) t, p);
          log.debug("Instantiated " + p + " for " + t);
        } catch (Exception e) {
          log.error("Failed to instantiate " + c, e);
        }
      }
    }
    @SuppressWarnings("rawtypes")
    PersistenceManager pm = new PersistenceManager();
    for (Class<?> c : ClassUtil.findSubclasses(VRObject.class)) {
      if (persistors.get(c) == null) {
        persistors.put(c, pm);
        log.debug("Instantiated " + pm + " for " + c);
      }
    }
    persistors.put(VRObject.class, pm);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void runAfterStartup() {
    for (String worldName : worldConfig.getWorld().keySet()) {
      WorldProperties wp = worldConfig.getWorld().get(worldName);
      log.info("Configuring world: " + worldName);
      World world = db.getWorldByName(worldName);
      if (world == null) {
        log.info("World " + worldName + " to be created as " + wp);
        world = new World();
      } else {
        log.info("World " + worldName + " already exists : " + world);
      }
      try {
        String className = wp.getType();
        if (!className.contains(".")) {
          // using default package
          className = "org.vrspace.server.obj." + className;
        }
        Class c = Class.forName(className);
        World w = (World) c.getDeclaredConstructor().newInstance();
      } catch (Exception e) {
        log.error("Error configuring world " + worldName, e);
      }
      BeanUtils.copyProperties(wp, world);
      db.save(world);
    }
    log.info("WorldManager ready");
  }

  // CHECKME: should this be here?
  public List<Class<?>> listClasses() {
    // gotta love oneliners :)
    return mappingContext.getManagedTypes().stream().filter(
        info -> !Modifier.isAbstract(info.getType().getModifiers()) && Entity.class.isAssignableFrom(info.getType()))
        .map(i -> i.getType()).collect(Collectors.toList());
  }

  public World getWorld(String name) {
    return db.getWorldByName(name);
  }

  public World getOrCreateWorld(String name) {
    World world = getWorld(name);
    if (world == null) {
      if (config.isCreateWorlds()) {
        world = db.save(new World(name));
      } else {
        throw new IllegalArgumentException("Unknown world: " + name);
      }
    }
    return world;
  }

  public Client getClientByName(String name) {
    Client ret = db.getClientByName(name);
    return (Client) updateCache(ret);
  }

  public <T extends Client> T getClientByName(String name, Class<T> cls) {
    T ret = db.getClientByName(name, cls);
    return (T) updateCache(ret);
  }

  public <T extends VRObject> T save(T obj) {
    T ret = db.save(obj); // CHECKME: writeback save/write ?
    cache.put(new ID(obj), ret);
    return ret;
  }

  public Set<VRObject> getRange(Client client, Point from, Point to) {
    return updateCache(db.getRange(client.getWorld().getId(), from, to));
  }

  public Set<VRObject> getPermanents(Client client) {
    return updateCache(db.getPermanents(client.getWorld().getId()));
  }

  private Set<VRObject> updateCache(Set<VRObject> objects) {
    HashSet<VRObject> ret = new HashSet<VRObject>();
    for (VRObject o : objects) {
      ret.add(updateCache(o));
    }
    return ret;
  }

  private VRObject updateCache(VRObject o) {
    // CHECKME: should this be null safe?
    if (o != null) {
      ID id = new ID(o);
      VRObject cached = cache.get(id);
      if (cached != null) {
        return cached;
      } else {
        o = db.get(o.getClass(), o.getId());
        // TODO: post-load operations
        persistors.get(o.getClass()).postLoad(o);
        cache.put(id, o);
        return o;
      }
    }
    return null;
  }

  /**
   * Add objects to client's current position
   * 
   * @param client  client adding objects
   * @param objects objects to add
   * @return list of added objects
   */
  public List<VRObject> add(Client client, List<VRObject> objects) {
    List<VRObject> ret = objects.stream().map(o -> {
      if (o.getPosition() == null && client.getPosition() != null) {
        o.setPosition(new Point(client.getPosition()));
      }
      o.setWorld(client.getWorld());
      if (o.getTemporary() == null && client.isGuest()) {
        o.setTemporary(true);
      }
      o = db.save(o);
      Ownership ownership = new Ownership(client, o);
      db.save(ownership);
      cache.put(o.getObjectId(), o);
      return o;
    }).collect(Collectors.toList());
    db.save(client);
    return ret;
  }

  public void remove(Client client, VRObject obj) {
    Ownership own = db.getOwnership(client.getId(), obj.getId());
    // CHECKME: remove invisible objects?
    if (own == null) {
      throw new SecurityException("Not yours to remove: " + obj.getClass().getSimpleName() + " " + obj.getId());
    }
    db.delete(own);
    db.save(client);
    delete(client, obj);
  }

  private void delete(Client client, VRObject obj) {
    cache.remove(obj.getObjectId());
    client.getWriteBack().delete(obj);
  }

  /**
   * Remote user login over websocket. Called from SessionManager, after websocket
   * session has been established. Uses session security context (principal) to
   * identify user and fetch/create the appropriate Client object from the
   * ClientFactory. May create a new guest client, if guest (anonymous)
   * connections are allowed.
   * 
   * @param session websocket session
   * @return Welcome message
   */
  @Transactional
  public Welcome login(ConcurrentWebSocketSessionDecorator session) {
    return login(session, User.class, userFactory);
  }

  /**
   * Login for remote servers
   * 
   * @see #login(ConcurrentWebSocketSessionDecorator)
   */
  @Transactional
  public Welcome serverLogin(ConcurrentWebSocketSessionDecorator session) {
    return login(session, RemoteServer.class, serverFactory);
  }

  /**
   * Common login procedure for both users and remote servers. This may change,
   * same for the time being.
   * 
   * @see #login(ConcurrentWebSocketSessionDecorator)
   * @param session       web socket session
   * @param clientClass   either User or RemoteServer
   * @param clientFactory either userFactory or serverFactory
   * @return
   */
  @Transactional
  public Welcome login(ConcurrentWebSocketSessionDecorator session, Class<? extends Client> clientClass,
      ClientFactory<? extends Client> clientFactory) {
    Principal principal = session.getPrincipal();
    HttpHeaders headers = session.getHandshakeHeaders();
    Map<String, Object> attributes = session.getAttributes();
    log.debug("Login principal: " + principal + " headers: " + headers + " attributes: " + attributes);
    // principal may be OAuth2AuthenticationToken, in that case getName() returns
    // token value, getAuthorizedClientRegistrationId() return the authority
    // (github, facebook...)
    Client client = null;
    if (session.getPrincipal() != null) {
      client = clientFactory.findClient(principal, db, headers, attributes);
      if (client == null) {
        throw new SecurityException("Unauthorized client " + session.getPrincipal().getName());
      }
    } else if (config.isGuestAllowed()) {
      client = clientFactory.createGuestClient(headers, attributes);
      if (client == null) {
        throw new SecurityException("Guest disallowed");
      }
      client.setPosition(new Point());
      client = db.save(client);
    } else {
      client = clientFactory.handleUnknownClient(headers, attributes);
      if (client == null) {
        throw new SecurityException("Unauthorized");
      }
    }
    client.setSession(session);
    login(client);
    return enter(client, defaultWorld());
  }

  /**
   * Stage 2 of login, executed once client has been identified. Does not depend
   * on websocket session, can be used for internal login, e.g. bots.
   * 
   * @param client
   */
  public void login(Client client) {
    client.setMapper(jackson);
    client.setPrivateMapper(privateJackson);
    client.setSceneProperties(sceneProperties.newInstance());
    WriteBack writeBack = new WriteBack(db);
    writeBack.setActive(config.isWriteBackActive());
    writeBack.setDelay(config.getWriteBackDelay());
    client.setWriteBack(writeBack);
    cache.put(new ID(client), client);
  }

  public World defaultWorld() {
    if (defaultWorld == null) {
      synchronized (this) {
        defaultWorld = db.getWorldByName("default");
        if (defaultWorld == null) {
          defaultWorld = db.save(new World("default", true));
          log.info("Created default world: " + defaultWorld);
        }
      }
    }
    return defaultWorld;
  }

  public Welcome enter(Client client, String worldName) {
    World world = getOrCreateWorld(worldName);
    return enter(client, world);
  }

  public Welcome enter(Client client, World world) {
    if (client.getWorld() != null) {
      if (client.getWorld().equals(world)) {
        throw new IllegalArgumentException("Already in world " + world);
      }
      // exit current world first
      exit(client);
    }
    if (!world.enter(client, this)) {
      throw new SecurityException("Client forbidden to enter the world");
    }

    // create audio stream
    streamManager.join(client, world);

    // client has now entered the world
    client.setWorld(world);
    // client.setActive(true); // DON'T
    client = save(client);

    Welcome ret = new Welcome(client, getPermanents(client));
    return ret;
  }

  public void startSession(Client client) throws SessionException {
    if (StringUtils.isNotBlank(client.getName())) {
      // new client can't have the same name as existing one
      Client existing = getClientByName(client.getName());
      if (existing != null && existing.getName() != null && client.getName().equals(existing.getName())
          && !existing.getId().equals(client.getId())) {
        throw new SessionException("Client named '" + client.getName() + "' already exists");
      }
    }
    sessionTracker.addSession(client);

    // client must have position to have scene
    // depending on how we create client that may not be the case
    if (client.getPosition() == null) {
      client.setPosition(new Point());
    }
    // client has now entered the world
    client.setActive(true);
    client = save(client);

    client.createScene(this);
  }

  @Transactional
  public void logout(Client client) {
    sessionTracker.remove(client);
    exit(client);
    // delete guest client
    if (client.isGuest()) {
      List<Ownership> owned = db.getOwned(client.getId());
      for (Ownership ownership : owned) {
        // CHECKME getOwned seems to return shallow copy!?
        VRObject ownedObject = cache.get(ownership.getOwned().getObjectId());
        if (ownedObject.isTemporary()) {
          // remove() doesn't free up cache
          delete(client, ownedObject);
          db.delete(ownership);
          log.debug("Deleted owned temporary " + ownership.getOwned().getObjectId());
        }
      }
      delete(client, client);
      log.debug("Deleted guest client " + client.getId());
    }
    client.getWriteBack().flush();
  }

  private void exit(Client client) {
    // notify all listeners that the client disconnected
    client.setActive(false);
    // TODO introduce LoginEvent
    VREvent ev = new VREvent(client, client);
    ev.addChange("active", false);
    client.notifyListeners(ev);
    if (client.getScene() != null) {
      // remove client from all scenes
      client.getScene().unpublish();
      // clear the scene to stop receiving events
      client.getScene().removeAll();
    }
    client.setListeners(null);
    // also remove the client from streaming session
    try {
      streamManager.disconnect(client);
    } catch (OpenViduException e) {
      log.error("Error disconnecting client " + client + " from streaming session", e);
    }
    // remove client from the world
    client.setWorld(null);
    client = save(client);
  }

  @Transactional
  public void dispatch(VREvent event) throws Exception {
    Client client = event.getClient();
    if (client == null) {
      throw new IllegalArgumentException("Event from uknown client " + event);
    }
    Scene scene = client.getScene();
    if (event instanceof ClientRequest && ((ClientRequest) event).isCommand()) {
      Command cmd = ((ClientRequest) event).getCommand();
      Object ret = cmd.execute(this, client);
      if (ret != null) {
        // synchronous command, send response
        client.sendMessage(ret);
      }
    } else {
      if (event.sourceIs(client)) {
        event.setSource(client);
      } else {
        if (scene == null) {
          throw new UnsupportedOperationException("Client has no scene " + client);
        }
        // find object source, either in scene or cache - it has to be seen by anyone
        VRObject obj = scene.get(event.getSourceID());
        if (obj == null) {
          obj = cache.get(event.getSourceID());
        }
        if (obj == null) {
          throw new UnsupportedOperationException("Unknown object: " + event.getSourceID());
          // } else if (!obj.isPermanent()) {
          // CKECKME: permanents only?
          // TODO test
          // throw new UnsupportedOperationException("Object not found in the scene: " +
          // event.getSourceID());
        }
        event.setSource(obj);
      }
      // CHECKME: cache ownership?
      Ownership ownership = db.getOwnership(client.getId(), event.getSource().getId());
      event.setOwnership(ownership);
      // dispatch
      dispatcher.dispatch(event);

      // write to the database after successful dispatch
      try {
        persistors.get(event.getSource().getClass()).persist(event);
      } catch (Exception e) {
        log.error("Error persisting " + event, e);
      }

      if (scene != null) {
        scene.update();
      }
    }
  }

}
