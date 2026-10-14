package yagen.waitmydawn.maa.model;

import java.util.*;

/** 最大余数法分配整数额度；权重未归一也不会超额，余数相同按输入顺序稳定决胜。 */
public final class WeightedAllocation {
    private WeightedAllocation() {}
    public static Map<String, Integer> allocate(Map<String, Integer> weights, int total) {
        var result = new LinkedHashMap<String, Integer>();
        if (weights.isEmpty() || total <= 0) return result;
        long sum = weights.values().stream().mapToLong(w -> Math.max(0, w)).sum();
        if (sum == 0) return result;
        var remainders = new LinkedHashMap<String, Long>();
        int used = 0;
        for (var entry : weights.entrySet()) {
            long product = (long) total * Math.max(0, entry.getValue());
            int value = (int) (product / sum);
            result.put(entry.getKey(), value); remainders.put(entry.getKey(), product % sum); used += value;
        }
        var order = new ArrayList<>(weights.keySet());
        order.sort(Comparator.comparingLong((String key) -> remainders.get(key)).reversed());
        for (int i = 0; i < total - used; i++) result.merge(order.get(i), 1, Integer::sum);
        return result;
    }
}
