package dev.fieldframe.api.face;

/** Chassis-local faces. Module placement and behaviour are always stored in these coordinates. */
public enum LocalFace {
    FRONT('F'), BACK('K'), LEFT('L'), RIGHT('R'), TOP('T'), BOTTOM('B');

    /** Single-letter label used in compact and colour-blind friendly displays. */
    public final char letter;

    LocalFace(char letter) {
        this.letter = letter;
    }

    public static final LocalFace[] ALL = values();

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static LocalFace byKey(String key) {
        for (LocalFace f : ALL) {
            if (f.key().equals(key)) {
                return f;
            }
        }
        return null;
    }

    public LocalFace opposite() {
        return switch (this) {
            case FRONT -> BACK;
            case BACK -> FRONT;
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            case TOP -> BOTTOM;
            case BOTTOM -> TOP;
        };
    }
}
