package net.minecraft.client.model;
import java.util.ArrayList;
import java.util.List;
public class ModelRenderer {
    public float textureWidth = 64, textureHeight = 32;
    public int textureOffsetX, textureOffsetY;
    public float rotationPointX, rotationPointY, rotationPointZ;
    public float rotateAngleX, rotateAngleY, rotateAngleZ;
    public boolean mirror, showModel = true, isHidden;
    public List<Object> cubeList = new ArrayList<>();
    public List<ModelRenderer> childModels;
    public final String boxName;
    public float offsetX, offsetY, offsetZ;
    public ModelRenderer(ModelBase model, String name) {
        boxName = name;
        model.boxList.add(this);
        textureWidth = model.textureWidth;
        textureHeight = model.textureHeight;
    }
    public ModelRenderer(ModelBase model) { this(model, null); }
    public ModelRenderer(ModelBase model, int u, int v) { this(model); setTextureOffset(u, v); }
    public ModelRenderer setTextureOffset(int u, int v) { textureOffsetX = u; textureOffsetY = v; return this; }
    public void setRotationPoint(float x, float y, float z) { rotationPointX = x; rotationPointY = y; rotationPointZ = z; }
    public ModelRenderer setTextureSize(int w, int h) { textureWidth = w; textureHeight = h; return this; }
    public void addChild(ModelRenderer c) { if (childModels == null) childModels = new ArrayList<>(); childModels.add(c); }
    public void render(float scale) {}
    public void renderWithRotation(float scale) {}
    public void postRender(float scale) {}
}
