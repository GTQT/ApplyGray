package applygray.integration.ae2.recipe;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RecipeFingerprintTest {

    @Test
    void canonicalNbtIgnoresCompoundInsertionOrder() {
        NBTTagCompound first = new NBTTagCompound();
        first.setInteger("z", 42);
        first.setString("a", "stable");
        NBTTagCompound firstNested = new NBTTagCompound();
        firstNested.setBoolean("second", true);
        firstNested.setLong("first", 7L);
        first.setTag("nested", firstNested);

        NBTTagCompound second = new NBTTagCompound();
        NBTTagCompound secondNested = new NBTTagCompound();
        secondNested.setLong("first", 7L);
        secondNested.setBoolean("second", true);
        second.setTag("nested", secondNested);
        second.setString("a", "stable");
        second.setInteger("z", 42);

        assertEquals(RecipeFingerprint.canonicalNbt(first), RecipeFingerprint.canonicalNbt(second));
    }

    @Test
    void canonicalNbtRetainsDifferentValues() {
        NBTTagCompound first = new NBTTagCompound();
        first.setInteger("amount", 1);
        NBTTagCompound second = new NBTTagCompound();
        second.setInteger("amount", 2);

        assertNotEquals(RecipeFingerprint.canonicalNbt(first), RecipeFingerprint.canonicalNbt(second));
    }
}
