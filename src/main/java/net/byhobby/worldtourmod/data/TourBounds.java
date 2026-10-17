package net.byhobby.worldtourmod.data;

public record TourBounds(Pos start, Pos finish) {
    public static final TourBounds EMPTY = new TourBounds(null, null);
}