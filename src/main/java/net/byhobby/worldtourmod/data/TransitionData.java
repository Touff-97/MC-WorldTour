package net.byhobby.worldtourmod.data;

// Mutable so relink() can update it
public class TransitionData {
    public String id;
    public Pos from;        // should coincide with the LAST point of a path
    public Pos to;          // should coincide with the FIRST point of a path
    public String fromPath; // id of the path ending at `from`, or null
    public String toPath;   // id of the path starting at `to`, or null
    public boolean active;  // true when both are linked
}