package dev.kizuna.bs3d.vanilla.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StationLayoutTest {
    @Test
    void scenesStayOutsideNativeSlotsAtUsualGuiScale() {
        var layout = StationLayout.beside(640, 360, 232, 176);
        assertTrue(layout.visible());
        assertTrue(layout.player().right() < 232);
        assertTrue(layout.interior().x() > 408);
        assertTrue(layout.interior().right() <= 640);
    }

    @Test
    void crampedScreenFallsBackInsteadOfCoveringRecipeControls() {
        assertFalse(StationLayout.beside(320, 240, 72, 176).visible());
        assertFalse(StationLayout.beside(640, 120, 232, 176).visible());
    }

    @Test
    void recipeBookRecenteringDoesNotReuseOldBounds() {
        var moved = StationLayout.beside(640, 360, 309, 176);
        assertTrue(moved.visible());
        assertTrue(moved.player().right() < 309);
        assertEquals(493, moved.interior().x());
    }
}
