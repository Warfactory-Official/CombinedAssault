package net.minecraft.client.model;
import net.minecraft.util.math.Vec3d;
public class PositionTextureVertex {
    public Vec3d vector3D; public float texturePositionX, texturePositionY;
    public PositionTextureVertex(float x, float y, float z, float u, float v) { this(new Vec3d(x, y, z), u, v); }
    public PositionTextureVertex(PositionTextureVertex o, float u, float v) { vector3D = o.vector3D; texturePositionX = u; texturePositionY = v; }
    public PositionTextureVertex(Vec3d p, float u, float v) { vector3D = p; texturePositionX = u; texturePositionY = v; }
    public PositionTextureVertex setTexturePosition(float u, float v) { return new PositionTextureVertex(this, u, v); }
}
