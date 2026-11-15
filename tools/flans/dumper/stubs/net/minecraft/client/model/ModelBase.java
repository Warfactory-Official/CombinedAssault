package net.minecraft.client.model;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
public class ModelBase {
    public int textureWidth = 64;
    public int textureHeight = 32;
    public List<ModelRenderer> boxList = new ArrayList<>();
    public void render(Entity e, float a, float b, float c, float d, float f, float g) {}
    public void setRotationAngles(float a, float b, float c, float d, float e, float f, Entity g) {}
}
