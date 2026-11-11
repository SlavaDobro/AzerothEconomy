package ru.terrawow.main.collection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Our own ModelEngine mount controller, WoW style (ModelEngine API by reflection, no compile
 * dependency). ModelEngine's built-in "flying" controller has no gravity, keeps the speed it has
 * (the mount drifts on its own) and dismounts as soon as Shift touches the ground.
 *
 * <p>Flying mount:
 *
 * <ul>
 *   <li>On the ground it runs with normal gravity. Space takes off at once. A fresh Shift press
 *       dismounts.
 *   <li>In the air Space goes up and Shift goes down. Touching the ground lands into running, even
 *       with Shift still held: you must release Shift and press it again to dismount.
 *   <li>Without WASD it brakes and hovers in place.
 * </ul>
 *
 * <p>Ground mount: WASD, Space jumps, Shift dismounts. Keys come from {@link Player#getCurrentInput()}
 * every tick, so a key can never get stuck.
 */
final class MountControl {
  private MountControl() {}

  private static final String API = "com.ticxo.modelengine.api.";
  private static boolean ready;
  private static Method getModeledEntity, getModels, getMountManager, mountDriver, dismountDriver;
  private static Method move, setVelocity, addVelocity, getVelocity, isOnGround, isInWater, getSpeed, jump, noFall;
  private static Method setHeadYaw, setPitch;
  private static Class<?> controllerType, supplierType;
  private static ClassLoader loader;
  private static boolean warned;

  private static void init() throws Exception {
    if (ready) return;
    var plugin = Bukkit.getPluginManager().getPlugin("ModelEngine");
    if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("ModelEngine не запущен");
    loader = plugin.getClass().getClassLoader();
    Class<?> api = cls("ModelEngineAPI");
    Class<?> modeled = cls("model.ModeledEntity");
    Class<?> active = cls("model.ActiveModel");
    Class<?> manager = cls("model.bone.manager.MountManager");
    Class<?> moveC = cls("nms.entity.wrapper.MoveController");
    Class<?> lookC = cls("nms.entity.wrapper.LookController");
    controllerType = cls("mount.controller.MountController");
    supplierType = cls("mount.controller.MountControllerSupplier");
    getModeledEntity = api.getMethod("getModeledEntity", Entity.class);
    getModels = modeled.getMethod("getModels");
    getMountManager = active.getMethod("getMountManager");
    mountDriver = manager.getMethod("mountDriver", Entity.class, supplierType);
    dismountDriver = manager.getMethod("dismountDriver");
    move = moveC.getMethod("move", float.class, float.class, float.class, float.class);
    setVelocity = moveC.getMethod("setVelocity", double.class, double.class, double.class);
    addVelocity = moveC.getMethod("addVelocity", double.class, double.class, double.class);
    getVelocity = moveC.getMethod("getVelocity");
    isOnGround = moveC.getMethod("isOnGround");
    isInWater = moveC.getMethod("isInWater");
    getSpeed = moveC.getMethod("getSpeed");
    jump = moveC.getMethod("jump");
    noFall = moveC.getMethod("nullifyFallDistance");
    setHeadYaw = lookC.getMethod("setHeadYaw", float.class);
    setPitch = lookC.getMethod("setPitch", float.class);
    ready = true;
  }

  private static Class<?> cls(String name) throws ClassNotFoundException {
    return Class.forName(API + name, true, loader);
  }

  /** Seats {@code driver} on the mob's model with our controller. */
  static boolean mount(Player driver, Entity mob, boolean canFly, Logger log) {
    try {
      init();
      Object modeled = getModeledEntity.invoke(null, mob);
      if (modeled == null) throw new IllegalStateException("у моба нет модели ModelEngine");
      for (Object model : ((Map<?, ?>) getModels.invoke(modeled)).values()) {
        Optional<?> mm = (Optional<?>) getMountManager.invoke(model);
        if (mm.isEmpty()) continue;
        Object supplier = Proxy.newProxyInstance(loader, new Class<?>[] {supplierType}, (proxy, m, args) -> {
          if (m.getName().equals("createController")) return controller((Entity) args[0], args[1], canFly, log);
          return object(proxy, m, args);
        });
        return (boolean) mountDriver.invoke(mm.get(), driver, supplier);
      }
      throw new IllegalStateException("в модели нет сиденья (кость mount, model{drive=true})");
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw new IllegalStateException("ModelEngine: " + e.getCause(), e.getCause());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("ModelEngine API недоступен: " + e, e);
    }
  }

  /** Equals / hashCode / toString for the proxies. */
  private static Object object(Object proxy, Method m, Object[] args) {
    return switch (m.getName()) {
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> "TerraWoWmain mount controller";
      default -> throw new UnsupportedOperationException(m.getName());
    };
  }

  private static Object controller(Entity driver, Object seat, boolean canFly, Logger log) {
    State s = new State(driver, seat, canFly);
    InvocationHandler h = (proxy, m, args) -> {
      switch (m.getName()) {
        case "getEntity":
          return s.driver;
        case "getMount":
          return s.seat;
        case "getInput":
          return s.input;
        case "setInput":
          s.input = args[0];
          return null;
        case "setCanDamageMount":
          s.canDamage = (boolean) args[0];
          return null;
        case "canDamageMount":
          return s.canDamage;
        case "setCanInteractMount":
          s.canInteract = (boolean) args[0];
          return null;
        case "canInteractMount":
          return s.canInteract;
        case "updatePassengerMovement":
          return null; // only the owner rides
        case "updateDirection": {
          Location l = s.driver.getLocation();
          setHeadYaw.invoke(args[0], l.getYaw());
          setPitch.invoke(args[0], l.getPitch());
          return null;
        }
        case "updateDriverMovement":
          try {
            s.tick(args[0], args[1]);
          } catch (Exception e) {
            if (!warned) {
              warned = true;
              log.log(Level.WARNING, "Коллекция: ошибка управления маунтом", e);
            }
            move.invoke(args[0], 0f, 0f, 0f, 0f);
          }
          return null;
        default:
          if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
          return object(proxy, m, args);
      }
    };
    return Proxy.newProxyInstance(loader, new Class<?>[] {controllerType}, h);
  }

  private static final class State {
    final Entity driver;
    final Object seat;
    final FlightLogic logic;
    volatile Object input; // ModelEngine fills it from packets; we read Paper's input instead
    boolean canDamage, canInteract;

    State(Entity driver, Object seat, boolean canFly) {
      this.driver = driver;
      this.seat = seat;
      this.logic = new FlightLogic(canFly);
    }

    void tick(Object mc, Object model) throws Exception {
      if (!(driver instanceof Player p)) return;
      Input in = p.getCurrentInput(); // fresh every tick: a key can never get stuck
      float front = in.isForward() ? 1 : in.isBackward() ? -1 : 0;
      float side = in.isLeft() ? 1 : in.isRight() ? -1 : 0;
      boolean ground = (boolean) isOnGround.invoke(mc); // the real flag (ModelEngine fakes the mob's)
      boolean water = (boolean) isInWater.invoke(mc);
      double speed = (float) getSpeed.invoke(mc);
      noFall.invoke(mc);
      FlightLogic.Act act = logic.step(in.isJump(), in.isSneak(), ground, water);
      switch (act) {
        case DISMOUNT -> {
          dismount(mc, model);
          return;
        }
        case FLY, FLY_UP, FLY_DOWN -> {
          Vector v = (Vector) getVelocity.invoke(mc);
          double keep = front == 0 && side == 0 ? 0.55 : 1.0; // no WASD: brake to a hover
          setVelocity.invoke(mc, v.getX() * keep, 0.0, v.getZ() * keep); // no gravity in the air
          if (act == FlightLogic.Act.FLY_UP) addVelocity.invoke(mc, 0.0, speed, 0.0);
          if (act == FlightLogic.Act.FLY_DOWN) addVelocity.invoke(mc, 0.0, -speed, 0.0);
        }
        default -> {}
      }
      move.invoke(mc, side, 0f, front, 1f); // WASD; on the ground gravity is on
      if (act == FlightLogic.Act.WALK_JUMP) jump.invoke(mc);
    }

    private static void dismount(Object mc, Object model) throws Exception {
      Optional<?> mm = (Optional<?>) getMountManager.invoke(model);
      if (mm.isPresent()) dismountDriver.invoke(mm.get());
      move.invoke(mc, 0f, 0f, 0f, 0f);
    }
  }
}
