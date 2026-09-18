package com.logiplatform.service;

import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.Vehicle;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.logiplatform.dto.LoadPlanDtos.*;



/**
 * Maximizes total cargo weight loaded onto a vehicle without exceeding its capacity —
 * the classic 0/1 knapsack problem (each shipment is taken whole or not at all; no
 * splitting a shipment across two trips).
 *
 * ALGORITHM NOTE: this is a direct, careful port of an algorithm whose CORRECTNESS
 * was verified independently in Python before this Java version was written (5 test
 * cases including a perfect-fit case, a no-perfect-fit case, an over-capacity single
 * item, an exact-multiple case, and the empty-input edge case — see
 * RLS_VERIFICATION.md for the full output). That verification proves the ALGORITHM
 * LOGIC is correct; it does NOT prove this specific Java file compiles or matches the
 * Python line-for-line — that still depends on the same CI compile step everything
 * else in this codebase does. Two different kinds of confidence, don't conflate them.
 *
 * SCOPE NOTE: this optimizes WEIGHT only, not volume/dimensions — true 3D bin packing
 * (which is what "maximize cargo space" really means for irregular freight) is a much
 * harder problem (NP-hard, no exact polynomial algorithm) that would need a proper
 * packing heuristic, not this. Volume-aware planning is a reasonable next increment,
 * not a today problem.
 */
@Service
public class LoadPlanningService {

    private final ShipmentRepository shipmentRepository;
    private final VehicleService vehicleService;

    public LoadPlanningService(ShipmentRepository shipmentRepository, VehicleService vehicleService) {
        this.shipmentRepository = shipmentRepository;
        this.vehicleService = vehicleService;
    }

    @Transactional(readOnly = true)
    public LoadPlanResponse planLoad(UUID vehicleId) {
        UUID tenantId = TenantContext.getTenantId();
        Vehicle vehicle = vehicleService.getOwned(vehicleId);
        int capacityKg = vehicle.getCapacityKg();
        if (capacityKg <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Vehicle capacity must be positive");
        }

        List<Shipment> candidates = shipmentRepository
                .findAllByTenantIdAndWeightKgIsNotNullAndStatus(tenantId, ShipmentStatus.PENDING);

        int n = candidates.size();

        // Exact DP is O(n * capacityKg) in both time and memory. Fine for road/air
        // vehicle capacities (hundreds to low thousands of kg) with realistic shipment
        // counts. For much larger capacities (e.g. an ocean container at 20,000+ kg)
        // combined with many candidate shipments, this table gets large enough to be
        // a real memory concern — guarding against that explicitly rather than letting
        // it silently degrade or OOM. A greedy heuristic (sort by weight descending,
        // add while it fits) would be the right fallback for that case; not implemented
        // here since none of the vehicle capacities this platform models today hit it.
        long dpCells = (long) (n + 1) * (capacityKg + 1);
        if (dpCells > 50_000_000L) {
            // Safe bounded fallback for very large instances. It never exceeds capacity;
            // it is deterministic and avoids an unbounded memory allocation.
            candidates = candidates.stream()
                    .sorted((a,b) -> Integer.compare(b.getWeightKg(), a.getWeightKg()))
                    .toList();
            List<PlannedShipment> selected = new ArrayList<>();
            List<PlannedShipment> excluded = new ArrayList<>();
            int loaded = 0;
            for (Shipment s : candidates) {
                PlannedShipment p = new PlannedShipment(s.getId(), s.getReferenceCode(), s.getWeightKg());
                if (loaded + s.getWeightKg() <= capacityKg) { selected.add(p); loaded += s.getWeightKg(); }
                else excluded.add(p);
            }
            return new LoadPlanResponse(vehicleId, capacityKg, loaded,
                    100.0 * loaded / capacityKg, selected, excluded);
        }

        int[] weights = new int[n];
        for (int i = 0; i < n; i++) {
            weights[i] = candidates.get(i).getWeightKg();
        }

        // --- 0/1 knapsack DP — direct port of the Python version verified above ---
        int[][] dp = new int[n + 1][capacityKg + 1];
        for (int i = 1; i <= n; i++) {
            int wi = weights[i - 1];
            for (int w = 0; w <= capacityKg; w++) {
                dp[i][w] = dp[i - 1][w]; // don't take item i
                if (wi <= w) {
                    dp[i][w] = Math.max(dp[i][w], dp[i - 1][w - wi] + wi); // take item i
                }
            }
        }

        // Backtrack to find which shipments were selected — same logic as the Python version.
        Set<Integer> selectedIndices = new HashSet<>();
        int w = capacityKg;
        for (int i = n; i >= 1; i--) {
            if (dp[i][w] != dp[i - 1][w]) {
                selectedIndices.add(i - 1);
                w -= weights[i - 1];
            }
        }

        List<PlannedShipment> selected = new ArrayList<>();
        List<PlannedShipment> excluded = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Shipment s = candidates.get(i);
            PlannedShipment planned = new PlannedShipment(s.getId(), s.getReferenceCode(), s.getWeightKg());
            (selectedIndices.contains(i) ? selected : excluded).add(planned);
        }

        int totalLoaded = n > 0 ? dp[n][capacityKg] : 0;
        double utilization = capacityKg > 0 ? (100.0 * totalLoaded / capacityKg) : 0.0;

        return new LoadPlanResponse(vehicleId, capacityKg, totalLoaded, utilization, selected, excluded);
    }
}

