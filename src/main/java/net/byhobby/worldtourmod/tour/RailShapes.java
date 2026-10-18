package net.byhobby.worldtourmod.tour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

/** Picks the rail shape for a block given its neighbours along the route (either may be null). */
public final class RailShapes {
    private RailShapes() {}

    public static BlockState rail(BlockPos prev, BlockPos cur, BlockPos next) {
        return Blocks.RAIL.defaultBlockState()
                .setValue(BlockStateProperties.RAIL_SHAPE, shape(prev, cur, next));
    }

    /** Horizontal direction from `from` to an adjacent `to` (at most 1 block up or down), else null. */
    private static Direction dirTo(BlockPos from, BlockPos to) {
        if (to == null) return null;
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ(), dy = to.getY() - from.getY();
        if (Math.abs(dy) > 1) return null;
        if (dz == 0 && Math.abs(dx) == 1) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (dx == 0 && Math.abs(dz) == 1) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        return null; // not adjacent (e.g. a transition jump or a vertical column)
    }

    private static RailShape shape(BlockPos prev, BlockPos cur, BlockPos next) {
        Direction dp = dirTo(cur, prev), dn = dirTo(cur, next);

        if (dn != null && next.getY() > cur.getY()) return ascending(dn);
        if (dp != null && prev.getY() > cur.getY()) return ascending(dp);

        if (dp != null && dn != null) {
            if (dp.getAxis() == dn.getAxis()) return straight(dn);
            return corner(dp, dn);
        }
        Direction d = dn != null ? dn : dp;
        return d == null ? RailShape.NORTH_SOUTH : straight(d);
    }

    private static RailShape straight(Direction d) {
        return d.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    private static RailShape corner(Direction a, Direction b) {
        boolean south = a == Direction.SOUTH || b == Direction.SOUTH;
        boolean east = a == Direction.EAST || b == Direction.EAST;
        if (south) return east ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST;
        return east ? RailShape.NORTH_EAST : RailShape.NORTH_WEST;
    }

    private static RailShape ascending(Direction d) {
        return switch (d) {
            case NORTH -> RailShape.ASCENDING_NORTH;
            case SOUTH -> RailShape.ASCENDING_SOUTH;
            case EAST -> RailShape.ASCENDING_EAST;
            default -> RailShape.ASCENDING_WEST;
        };
    }
}