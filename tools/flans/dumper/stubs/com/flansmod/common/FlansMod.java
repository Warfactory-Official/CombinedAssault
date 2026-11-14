package com.flansmod.common;
public class FlansMod {
    public static final Log log = new Log();
    public static class Log { public void warn(String s) { System.err.println(s); } public void error(String s) { System.err.println(s); } public void info(String s) {} }
    public static void Assert(boolean b, String s) { if (!b) throw new AssertionError(s); }
}
