package gg.modl.backend.settings.service;

import gg.modl.backend.settings.data.PunishmentType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PunishmentTypeIndex {

    public static Map<Integer, PunishmentType> byOrdinal(List<PunishmentType> types) {
        Map<Integer, PunishmentType> map = new HashMap<>(types.size() * 2);
        for (PunishmentType type : types) {
            if (type.getOrdinal() != null) {
                map.put(type.getOrdinal(), type);
            }
        }
        return map;
    }
}
