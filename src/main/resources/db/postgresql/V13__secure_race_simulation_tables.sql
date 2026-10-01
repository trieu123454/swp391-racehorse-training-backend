-- Simulation data is served only through the authenticated Spring API.
ALTER TABLE race_simulations ENABLE ROW LEVEL SECURITY;
ALTER TABLE race_simulation_horses ENABLE ROW LEVEL SECURITY;
ALTER TABLE race_simulation_metrics ENABLE ROW LEVEL SECURITY;
