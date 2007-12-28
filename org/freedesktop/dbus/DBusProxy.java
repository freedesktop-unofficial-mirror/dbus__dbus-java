/*
   D-Bus Java Implementation
   Copyright (c) 2005-2006 Matthew Johnson

   This program is free software; you can redistribute it and/or modify it
   under the terms of either the GNU General Public License Version 2 or the
   Academic Free Licence Version 2.1.

   Full licence texts are included in the COPYING file with this program.
*/
package org.freedesktop.dbus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.io.PrintWriter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import cx.ath.matthew.debug.Debug;
import cx.ath.matthew.utils.Hexdump;

import org.freedesktop.dbus.exceptions.DBusException;

import static org.freedesktop.dbus.Gettext._;

public class DBusProxy
{
   private static Map<String,Class<? extends DBusInterface>> typecache;
   static {
      typecache = new HashMap<String,Class<? extends DBusInterface>>();
   }
   private static String getImport(Class c)
   {
      if (null == c) return "";
      if (Void.TYPE.equals(c)) return "";
      while (c.isArray()) c = c.getComponentType();
      if (c.isPrimitive()) return "";
      String name = c.getName();
      name = AbstractConnection.dollar_pattern.matcher(name).replaceAll(".");
      return "import "+name+";";
   }
   private static String boxed(String c) {
      if (c.equals("boolean")) return "Boolean";
      if (c.equals("int")) return "Integer";
      if (c.equals("short")) return "Short";
      if (c.equals("long")) return "Long";
      if (c.equals("double")) return "Double";
      if (c.equals("byte")) return "Byte";
      if (c.equals("float")) return "Float";
      if (c.equals("char")) return "Char";
      return c;
   }
   @SuppressWarnings("unchecked")
   public static Object getProxy(Class[] types, AbstractConnection conn, RemoteObject ro) throws DBusException
   {
      if (Debug.debug) Debug.print(Debug.DEBUG, "Creating proxy type: "+Arrays.deepToString(types)+" connection: "+conn+" Details: "+ro);
      // dynamic runtime compilation
      if (DBusCodeGen.canCompile()) {
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Creating compiled proxy");
         DBusCodeGen gen = new DBusCodeGen();

         // pick a random name, check whether we have it already
         String classname = "_"+(long) Math.abs(ro.hashCode());
         if (null != typecache.get(classname)) return typecache.get(classname);

         Vector<Method> methods = new Vector<Method>();

         PrintWriter out = gen.startClass("org.freedesktop.dbus.dynamic", classname);

         // required imports
         out.println("import org.freedesktop.dbus.*;");
         out.println("import org.freedesktop.dbus.exceptions.*;");
         if (Debug.debug)
            out.println("import cx.ath.matthew.debug.Debug;");
         for (Class c: types) {
            out.print("import ");
            out.print(AbstractConnection.dollar_pattern.matcher(c.getName()).replaceAll("."));
            out.println(';');
            // push methods for later use
            for (Method m: c.getDeclaredMethods()) {
               methods.add(m);
               if(null != m.getReturnType() && !Void.TYPE.equals(m.getReturnType())) 
                  out.println(getImport(m.getReturnType()));
               for (Class p: m.getParameterTypes()) 
                  out.println(getImport(p));
            }
         }
         out.println();

         // implements the list of interfaces
         out.print("public class ");
         out.print(classname);
         out.print(" implements ");
         for (Class c: types) {
            out.print(c.getSimpleName());
            out.print(',');
         }
         out.println("DBusInterface {");
         out.println();

         // local variables
         out.println("private AbstractConnection conn;");
         out.println("private RemoteObject ro;");
         out.println();

         // constructor
         out.print("public ");
         out.print(classname);
         out.println("(AbstractConnection conn, RemoteObject ro) { this.conn = conn; this.ro = ro; }");

         // local methods
         out.println("public boolean isRemote() { return true; }");
         out.print("public String toString() { return \"");
         out.print(ro.toString());
         out.println("\"; }");
         out.println();

         // iterate over methods
         for (Method m: methods) {
            out.print("public ");

            boolean prim = true;

            // return type
            if (null == m.getReturnType())
               out.print("void");
            else {
               out.print(m.getReturnType().getSimpleName());
               prim &= m.getReturnType().isPrimitive();
            }
            out.print(' ');

            // method name
            out.print(m.getName());

            // parameters
            out.print('(');
            Class[] cs = m.getParameterTypes();
            char n = 'a';
            for (int i = 0; i < cs.length; i++, n++) {
               out.print(cs[i].getSimpleName());
               out.print(' ');
               out.print(n);
               if ((i + 1) != cs.length)
                  out.print(',');
               prim &= cs[i].isPrimitive();
            }
            out.println(") {");

            // all arguments are primative, can do it more efficiently
            if (prim) {
               //<<END
               Message m = new Message(Message.Endian.BIG, Message.MessageType.METHOD_CALL, 0);
               byte[][] bytes = new byte[2][];
               //TODO: create bytes
               // - calculate body size
               int bsize = 0;
               for (Class c: m.getParameterTypes()) {
                  if (c.equals(Byte.TYPE))
                     bsize += 1;
                  else if (c.equals(Short.TYPE))
                     bsize += 2;
                  else if (c.equals(Boolean.TYPE)||
                        c.equals(Integer.TYPE)||
                        c.equals(Float.TYPE))
                     bsize += 4;
                  else
                     bsize += 8;
               }
               // - calculate signature
               //END
               String sig = Marshalling.getDBusType(m.getParameterTypes());
               out.print("byte[] headers = new byte[] { ");
               byte[] bsizes = new byte[4];
               Message.marshallintBig(bsize, bsizes, 0, 4);
               out.print(Hexdump.toByteArray(bsizes));
               out.print(",0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,");
               Message.marshallintBig(Message.HeaderField.PATH, bsizes, 0, 4);
               out.print(Hexdump.toByteArray(bsizes));
               // - calculate header size
               //   - uua(yv)
               //     - u = body length
               //     - u = serial
               //     - a(yv) = 
               //       { ( PATH, [ OBJECT_PATH, ro.path ] ),
               //         ( DESTINATION, [ STRING, ro.dest ] ),
               //         ( INTERFACE, [ STRING, ro.iface ] ),
               //         ( MEMBER, [ STRING, m.getName() ] ) 
               //         ( SIGNATURE, [ SIGNATURE, sig ] ) }
               // - create literal headers (sans serial)
               out.println("};");
               //<<END
               // - insert serial
               Message.marshallintBig(m.getSerial(),headers, 4, 4);
               Message.marshallintBig(harrlen,headers, 8, 4);
               // - create body
               byte[] body = new byte[bsize];
               bytes[1] = body;
               bytes[2] = headers;
               // - insert body
               int ofs = 0;
               //END
               char ch = 'a';
               //<<END
               for (Class c: m.getParameterTypes()) {
                  if (c.equals(Byte.TYPE))
                     body[ofs++] = "+(ch++)+";
                  else if (c.equals(Short.TYPE)) {
                     marshallintBig("+(ch++)+", body, ofs, 2);
                     ofs += 2;
                  } else if (c.equals(Integer.TYPE)) {
                     marshallintBig("+(ch++)+", body, ofs, 4);
                     ofs += 4;
                  } else if (c.equals(Long.TYPE)) {
                     marshallintBig("+(ch++)+", body, ofs, 8);
                     ofs += 8;
                  } else if (c.equals(Boolean.TYPE)) {
                     marshallintBig("+(ch++)+"?1:0, body, ofs, 4);
                     ofs += 4;
                  } else if (c.equals(Float.TYPE)) {
                     marshallintBig(Float.floatToIntBits("+(ch++)+"), body, ofs, 4);
                     ofs += 4;
                  } else if (c.equals(Double.TYPE)) {
                     marshallintBig(Double.doubleToLongBits("+(ch++)+"), body, ofs, 8);
                     ofs += 8;
                  }
               }
               m.addPayload(bytes);
               conn.queueOutgoing(call);
               //END
               if (!m.isAnnotationPresent(DBus.Method.NoReply.class)) {
                  out.println("Message reply = call.getReply();");
                  out.println("if (null == reply) throw new DBus.Error.NoReply(_("No reply within specified time"));");
                  out.println("");
                  out.println("if (reply instanceof Error)");
                  out.println("((Error) reply).throwException();");
                  out.println("");
                  out.println("byte[] body = reply.getBody();");
                  if (m.getReturnType().equals(Integer.TYPE)
                        || m.getReturnType().equals(Short.TYPE)
                        || m.getReturnType().equals(Long.TYPE))
                     out.println("return Message.demarshallint(body, 0, reply.getEndianness(), body.length);");
                  } else if (m.getReturnType().equals(Double.TYPE)) {
                     out.println("return Double.longBitsToDouble(Message.demarshallint(body, 0, reply.getEndianness(), 8));");
                  } else if (m.getReturnType().equals(Float.TYPE)) {
                     out.println("return Float.intBitsToFloat(Message.demarshallint(body, 0, reply.getEndianness(), 4));");
                  } else if (m.getReturnType().equals(Byte.TYPE)) {
                     out.println("return body[0];");
                  } else if (m.getReturnType().equals(Boolean.TYPE)) {
                     out.println("return 1 == Message.demarshallint(body, 0, reply.getEndianness(), body.length);");
                  }
               }
            }

            // all other cases
            else {

               // invoke using proxy for now
               out.println("java.lang.reflect.Method meth = null;");
               out.println("for (Class cl:getClass().getInterfaces()) {");
               out.println("try {");
               out.print("meth = cl.getMethod(\"");
               out.print(m.getName());
               out.print('"');
               for (Class c: m.getParameterTypes()) {
                  out.print(',');
                  out.print(c.getSimpleName());
                  out.print(".class");
               }
               out.println(");");
               out.println("break;");
               out.println("} catch (NoSuchMethodException NSMe) {}");
               out.println('}');
               out.print("Object rv = RemoteInvocationHandler.executeRemoteMethod(ro, meth, conn, RemoteInvocationHandler.CALL_TYPE_SYNC, null");
               for (char q = 'a'; q < n; q++) {
                  out.print(",(Object)");
                  out.print(q);
               }
               out.println(");");

               // check if we need to return a value
               if(null != m.getReturnType() && !Void.TYPE.equals(m.getReturnType())) {
                  out.print("return (");
                  out.print(boxed(m.getReturnType().getSimpleName()));
                  out.println(") rv;");
               }
            }
            out.println('}');
            out.println();
         }
         out.println('}');

         // compile and instantiate
         try {
            return gen.loadClasses()[0].getConstructor(AbstractConnection.class, RemoteObject.class).newInstance(conn, ro);
         } catch (Exception e) {
            if (AbstractConnection.EXCEPTION_DEBUG && Debug.debug)
               Debug.print(e);
            throw new DBusException(_("Failed to instantiate compiled proxy class: ")+e);
         }
      }

      // runtime proxies
      else {
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Creating handler proxy");
         return Proxy.newProxyInstance(DBusProxy.class.getClassLoader(), 
            types, new RemoteInvocationHandler(conn, ro));
      }
   }
}
