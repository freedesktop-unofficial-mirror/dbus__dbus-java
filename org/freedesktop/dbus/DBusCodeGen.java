/*
   D-Bus Java Implementation
   Copyright (c) 2005-2006 Matthew Johnson

   This program is free software; you can redistribute it and/or modify it
   under the terms of either the GNU General Public License Version 2 or the
   Academic Free Licence Version 2.1.

   Full licence texts are included in the COPYING file with this program.
*/
package org.freedesktop.dbus;

import static org.freedesktop.dbus.Gettext._;

import com.sun.tools.javac.Main;

import cx.ath.matthew.debug.Debug;

import org.freedesktop.dbus.exceptions.DBusCodeException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import java.net.URL;
import java.net.URLClassLoader;

import java.util.Arrays;
import java.util.Stack;
import java.util.Vector;

public class DBusCodeGen<T>
{
   private static File tempdir;
   private static boolean compilation = false;
   private static ClassLoader localLoader;
   private static String classpath;
   private static String[] bootargs;
   private static PrintWriter out;
   static {
      try {
         boolean a = AbstractConnection.EXCEPTION_DEBUG;
         if (Debug.debug) Debug.print(Debug.INFO, "Initializing dynamic compilation");
         Class c = Class.forName("com.sun.tools.javac.Main");
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Got compiler class: "+c);
         tempdir = File.createTempFile("compile", "");
         tempdir.delete();
         tempdir.mkdirs();
         tempdir.deleteOnExit();
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Schedule delete: "+tempdir);
         classpath = tempdir.getPath()+":"+System.getProperty("java.class.path");

         if (Debug.debug) out = new PrintWriter(System.err);
         else {
            File f = new File(tempdir, "compile.log");
            f.deleteOnExit();
            out = new PrintWriter(new FileOutputStream(f));
         }

         bootargs = new String[4];
         bootargs[0] = "-classpath";
         bootargs[1] = classpath;
         bootargs[2] = "-d";
         bootargs[3] = tempdir.getPath();
         ClassLoader parentLoader = DBusCodeGen.class.getClassLoader();
         localLoader = new URLClassLoader(new URL[] { tempdir.toURI().toURL() }, parentLoader);
         compilation = true;
      } catch (Exception e) {
         if (AbstractConnection.EXCEPTION_DEBUG && Debug.debug) Debug.print(e);
      }
   }
   public static boolean canCompile() { return compilation; }
   private Vector<String> classes;
   private Vector<String> files;
   private Vector<PrintWriter> writers;
   public DBusCodeGen() throws DBusCodeException
   {
      if (!compilation) throw new DBusCodeException(_("Compilation is not enabled, either the JDK isn't installed or cannot write to temporary directory."));
      classes = new Vector<String>();
      files = new Vector<String>();
      writers = new Vector<PrintWriter>();
   }
   /**
    * May be called multiple times, get a PrintWriter for a new class.
    */
   public PrintWriter startClass(String pack, String classname) throws DBusCodeException
   {
      String dir = pack.replace(".","/");
      String filename = dir+"/"+classname;
      String cname = pack+"."+classname;
      if (Debug.debug) Debug.print(Debug.VERBOSE, "Start compilation of "+cname+" = "+filename);
      classes.add(cname);
      files.add(filename);
      File d = new File(tempdir,dir);
      File j = new File(tempdir,filename+".java");
      Stack<File> s = new Stack<File>();
      try {
         d.mkdirs();
         while (null != d && ! d.equals(tempdir)) {
            s.push(d);
            d = d.getParentFile();
         }
         while (s.size() != 0) {
            d = s.pop();
            d.deleteOnExit();
            if (Debug.debug) Debug.print(Debug.VERBOSE, "Schedule delete: "+d);
         }
         j.deleteOnExit();
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Schedule delete: "+j);
         PrintWriter o = new PrintWriter(new BufferedOutputStream(new FileOutputStream(j)));
         writers.add(o);
         o.print("package ");
         o.print(pack);
         o.println(';');
         return o;
      } catch (IOException IOe) {
         if (AbstractConnection.EXCEPTION_DEBUG && Debug.debug) Debug.print(IOe);
         throw new DBusCodeException(_("Failed to create temporary file for dynamic compilation, compilation failed. Error: ")+IOe.getMessage());
      }
   }
   /**
    * Compile and load all classes written so far
    */
   @SuppressWarnings("unchecked")
   public Class<T>[] loadClasses() throws DBusCodeException
   {
      if (Debug.debug) Debug.print(Debug.VERBOSE, "Compiling "+classes.size()+" classes");
      if (0 == classes.size()) return new Class[0];
      String[] args = new String[bootargs.length+classes.size()];
      System.arraycopy(bootargs, 0, args, 0, 4);
      int i = 4;
      for (String f: files) {
         File classfile = new File(tempdir, f+".class");
         classfile.deleteOnExit();
         if (Debug.debug) Debug.print(Debug.VERBOSE, "Schedule delete: "+classfile);
         writers.get(i-4).close();
         args[i++] = tempdir+"/"+f+".java";
      }
      try {
         if (Debug.debug) Debug.print(Debug.DEBUG, "Main.compile("+Arrays.deepToString(args)+")");
         int errorCode = Main.compile(args, out);
         if (0 != errorCode) throw new Exception("errorCode="+errorCode);
      } catch (Exception e) {
         if (AbstractConnection.EXCEPTION_DEBUG && Debug.debug) Debug.print(e);
         throw new DBusCodeException(_("Exception occured during compilation. Dynamic class compilation failed. Error: ")+e.getMessage()); 

      }
      Class<T>[] cs = new Class[classes.size()];
      i = 0;
      for (String f: classes) {
         try {
            cs[i++] = (Class<T>) localLoader.loadClass(f);
         } catch (ClassNotFoundException CNFe) {
            if (AbstractConnection.EXCEPTION_DEBUG && Debug.debug) Debug.print(CNFe);
            throw new DBusCodeException(_("Failed to reload compiled class, dynamic compilation failed. Error: ")+CNFe.getMessage());
         }
      }
      classes = new Vector<String>();
      files = new Vector<String>();
      writers = new Vector<PrintWriter>();
      return cs;
   }
}
