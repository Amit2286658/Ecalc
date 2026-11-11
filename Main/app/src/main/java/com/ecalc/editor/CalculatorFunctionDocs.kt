package com.ecalc

/**
 * Curated rich documentation for the Basic Calculator’s Functions-tab hold popups.
 *
 * Every entry is authored against the canonical stdlib sources under
 * `Main/Library` (the engine’s own `.math` definitions, exported as-is from the same
 * sources the byte-identical `stdlib.mathc` is compiled from), so formulas, parameter
 * names and units quoted here are exactly what the native engine evaluates.
 *
 * Accessed via [richPopupText]; functions not present here fall back to the existing
 * [CalculatorCatalogItem.toPopupInfoText] generic popup.
 */
object CalculatorFunctionDocs {

    /** One rich popup entry: what the function is, what each parameter means, what comes back. */
    data class FnDoc(val definition: String, val params: List<String>, val returns: String)

    private fun d(definition: String, params: List<String>, returns: String): FnDoc =
        FnDoc(definition, params, returns)

    private val BY_NAME: Map<String, FnDoc> = mapOf(
        "J_to_Wh" to d(
            "Unit conversion from joules (J) to watt-hours (Wh). Formula: 1 J = x / 3600 Wh",
            listOf("x — a quantity in joules (J)"),
            "the equivalent value in watt-hours (Wh)"
        ),
        "J_to_cal" to d(
            "Unit conversion from joules (J) to calories (cal). Formula: 1 J = x / 4.184 cal",
            listOf("x — a quantity in joules (J)"),
            "the equivalent value in calories (cal)"
        ),
        "J_to_kJ" to d(
            "Unit conversion from joules (J) to kilojoules (kJ). Formula: 1 J = x / 1000 kJ",
            listOf("x — a quantity in joules (J)"),
            "the equivalent value in kilojoules (kJ)"
        ),
        "J_to_kWh" to d(
            "Unit conversion from joules (J) to kilowatt-hours (kWh). Formula: 1 J = x / 3600000 kWh",
            listOf("x — a quantity in joules (J)"),
            "the equivalent value in kilowatt-hours (kWh)"
        ),
        "J_to_kcal" to d(
            "Unit conversion from joules (J) to kilocalories (kcal). Formula: 1 J = x / 4184 kcal",
            listOf("x — a quantity in joules (J)"),
            "the equivalent value in kilocalories (kcal)"
        ),
        "L_to_gal" to d(
            "Unit conversion from litres (L) to US gallons (gal). Formula: 1 L = x / 3.785411784 gal",
            listOf("x — a quantity in litres (L)"),
            "the equivalent value in US gallons (gal)"
        ),
        "L_to_m3" to d(
            "Unit conversion from litres (L) to cubic metres (m³). Formula: 1 L = x·0.001 m3",
            listOf("x — a quantity in litres (L)"),
            "the equivalent value in cubic metres (m³)"
        ),
        "L_to_mL" to d(
            "Unit conversion from litres (L) to millilitres (mL). Formula: 1 L = x·1000 mL",
            listOf("x — a quantity in litres (L)"),
            "the equivalent value in millilitres (mL)"
        ),
        "MW_to_W" to d(
            "Unit conversion from megawatts (MW) to watts (W). Formula: 1 MW = x·1000000 W",
            listOf("x — a quantity in megawatts (MW)"),
            "the equivalent value in watts (W)"
        ),
        "N_to_dyn" to d(
            "Unit conversion from newtons (N) to dynes (dyn). Formula: 1 N = x·100000 dyn",
            listOf("x — a quantity in newtons (N)"),
            "the equivalent value in dynes (dyn)"
        ),
        "N_to_kN" to d(
            "Unit conversion from newtons (N) to kilonewtons (kN). Formula: 1 N = x / 1000 kN",
            listOf("x — a quantity in newtons (N)"),
            "the equivalent value in kilonewtons (kN)"
        ),
        "N_to_lbf" to d(
            "Unit conversion from newtons (N) to pounds-force (lbf). Formula: 1 N = x / 4.4482216152605 lbf",
            listOf("x — a quantity in newtons (N)"),
            "the equivalent value in pounds-force (lbf)"
        ),
        "Pa_to_atm" to d(
            "Unit conversion from pascals (Pa) to atmospheres (atm). Formula: 1 Pa = x / 101325 atm",
            listOf("x — a quantity in pascals (Pa)"),
            "the equivalent value in atmospheres (atm)"
        ),
        "Pa_to_bar" to d(
            "Unit conversion from pascals (Pa) to bar. Formula: 1 Pa = x / 100000 bar",
            listOf("x — a quantity in pascals (Pa)"),
            "the equivalent value in bar"
        ),
        "Pa_to_kPa" to d(
            "Unit conversion from pascals (Pa) to kilopascals (kPa). Formula: 1 Pa = x / 1000 kPa",
            listOf("x — a quantity in pascals (Pa)"),
            "the equivalent value in kilopascals (kPa)"
        ),
        "Pa_to_psi" to d(
            "Unit conversion from pascals (Pa) to pounds per square inch (psi). Formula: 1 Pa = x / 6894.757293168 psi",
            listOf("x — a quantity in pascals (Pa)"),
            "the equivalent value in pounds per square inch (psi)"
        ),
        "W_to_MW" to d(
            "Unit conversion from watts (W) to megawatts (MW). Formula: 1 W = x / 1000000 MW",
            listOf("x — a quantity in watts (W)"),
            "the equivalent value in megawatts (MW)"
        ),
        "W_to_hp" to d(
            "Unit conversion from watts (W) to mechanical horsepower (hp). Formula: 1 W = x / 745.699872 hp",
            listOf("x — a quantity in watts (W)"),
            "the equivalent value in mechanical horsepower (hp)"
        ),
        "W_to_kW" to d(
            "Unit conversion from watts (W) to kilowatts (kW). Formula: 1 W = x / 1000 kW",
            listOf("x — a quantity in watts (W)"),
            "the equivalent value in kilowatts (kW)"
        ),
        "Wh_to_J" to d(
            "Unit conversion from watt-hours (Wh) to joules (J). Formula: 1 Wh = x·3600 J",
            listOf("x — a quantity in watt-hours (Wh)"),
            "the equivalent value in joules (J)"
        ),
        "abs" to d(
            "absolute value |x|, magnitude without sign. Define: abs(x) = @abs(x)",
            listOf("x — the real value whose magnitude is wanted"),
            "a non-negative real number"
        ),
        "abs_diff" to d(
            "absolute difference |a − b|. Define: abs_diff(a, b) = (a < b) ? (b - a) : (a - b)",
            listOf("a — first value", "b — second value"),
            "a non-negative real number"
        ),
        "acos" to d(
            "inverse cosine, principal value. Define: acos(x) = @acos(x)",
            listOf("x — ratio value to invert, within the function’s valid domain"),
            "an angle in radians, principal value in [0, π]"
        ),
        "acosec" to d(
            "inverse cosecant, principal value. Define: acosec(x) = asin(1 / x)",
            listOf("x — ratio value to invert, within the function’s valid domain"),
            "an angle in radians, principal value"
        ),
        "acot" to d(
            "inverse cotangent, principal value. Define: acot(x) = atan(1 / x)",
            listOf("x — ratio value to invert, within the function’s valid domain"),
            "an angle in radians, principal value"
        ),
        "acsc" to d(
            "inverse cosecant, principal value. Define: acsc(x) = asin(1 / x)",
            listOf("x — ratio value to invert, within the function’s valid domain"),
            "an angle in radians, principal value"
        ),
        "actual_specimen_size" to d(
            "real specimen size from magnified size and magnification. Define: actual_specimen_size(image_size, magnification) = image_size / magnification",
            listOf("image_size — size measured on the image", "magnification — magnification used"),
            "the real size in the input’s length unit"
        ),
        "aerobic_atp_yield" to d(
            "total aerobic ATP yield per glucose molecule. Define: aerobic_atp_yield(glucose_moles) = 38·glucose_moles",
            listOf("glucose_moles — moles of glucose fully oxidised"),
            "an ATP molecule count per glucose"
        ),
        "alligation_ratio" to d(
            "alligation ratio for mixing two ingredients of different prices. Define: alligation_ratio(cheaper, dearer, mean_p) = (dearer - mean_p) / (mean_p - cheaper)",
            listOf("cheaper — price of the cheaper ingredient", "dearer — price of the dearer ingredient", "mean_p — target mean price of the mixture"),
            "a dimensionless ratio/percentage"
        ),
        "alveolar_ventilation" to d(
            "alveolar ventilation = (tidal volume − dead space) × rate. Define: alveolar_ventilation(tidal_vol_ml, dead_space_ml, resp_rate_bpm) = (tidal_vol_ml - dead_space_ml)·resp_rate_bpm",
            listOf("tidal_vol_ml — tidal volume (mL)", "dead_space_ml — anatomical dead space (mL)", "resp_rate_bpm — breaths per minute"),
            "a ventilation rate in mL/min"
        ),
        "ang_momentum" to d(
            "angular momentum L = I·ω. Define: ang_momentum(inertia_i, omega) = inertia_i·omega",
            listOf("inertia_i — moment of inertia I (kg·m²)", "omega — angular velocity ω (rad/s)"),
            "an angular momentum in kg·m²/s"
        ),
        "ap_nth" to d(
            "nth term of an arithmetic progression aₙ = a + (n−1)d. Define: ap_nth(a, d, n) = a + (n - 1)·d",
            listOf("a — first term a₁", "d — common difference d", "n — term index (1-based)"),
            "the nth term value"
        ),
        "arcmin_to_deg" to d(
            "Unit conversion from arcminutes (′) to degrees (°). Formula: 1 arcmin = m / 60 deg",
            listOf("m — a quantity in arcminutes (′)"),
            "the equivalent value in degrees (°)"
        ),
        "arcsec_to_deg" to d(
            "Unit conversion from arcseconds (″) to degrees (°). Formula: 1 arcsec = s / 3600 deg",
            listOf("s — a quantity in arcseconds (″)"),
            "the equivalent value in degrees (°)"
        ),
        "arcsec_to_rad" to d(
            "Unit conversion from arcseconds (″) to radians (rad). Formula: 1 arcsec = s·π / (180·3600) rad",
            listOf("s — a quantity in arcseconds (″)"),
            "the equivalent value in radians (rad)"
        ),
        "arrhenius_k" to d(
            "Arrhenius rate constant k = A·e^−Ea/RT. Define: arrhenius_k(pre_exp_a, ea_j_mol, temp_k) = pre_exp_a·exp(-ea_j_mol / (R_gas·temp_k))",
            listOf("pre_exp_a — pre-exponential factor A", "ea_j_mol — activation energy (J/mol)", "temp_k — temperature (K)"),
            "a rate constant in the same unit as the pre-exponential A"
        ),
        "arrhenius_k2" to d(
            "rate constant at a second temperature (two-point Arrhenius). Define: arrhenius_k2(k1, ea_j_mol, t1_k, t2_k) = k1·exp((ea_j_mol / R_gas)·((1 / t1_k) - (1 / t2_k)))",
            listOf("k1 — rate constant at t1_k", "ea_j_mol — activation energy (J/mol)", "t1_k — first temperature (K)", "t2_k — second temperature (K)"),
            "a rate constant in the same unit as k1"
        ),
        "asec" to d(
            "inverse secant, principal value. Define: asec(x) = acos(1 / x)",
            listOf("x — ratio value to invert, within the function’s valid domain"),
            "an angle in radians, principal value"
        ),
        "asin" to d(
            "inverse sine, principal value. Define: asin(x) = @asin(x)",
            listOf("x — ratio value to invert, within the function’s valid domain"),
            "an angle in radians, principal value in [−π/2, π/2]"
        ),
        "atan" to d(
            "inverse tangent, principal value. Define: atan(x) = @atan(x)",
            listOf("x — ratio value to invert, within the function’s valid domain"),
            "an angle in radians, principal value in (−π/2, π/2)"
        ),
        "atm_to_Pa" to d(
            "Unit conversion from atmospheres (atm) to pascals (Pa). Formula: 1 atm = x·101325 Pa",
            listOf("x — a quantity in atmospheres (atm)"),
            "the equivalent value in pascals (Pa)"
        ),
        "atm_to_bar" to d(
            "Unit conversion from atmospheres (atm) to bar. Formula: 1 atm = x·1.01325 bar",
            listOf("x — a quantity in atmospheres (atm)"),
            "the equivalent value in bar"
        ),
        "atm_to_psi" to d(
            "Unit conversion from atmospheres (atm) to pounds per square inch (psi). Formula: 1 atm = x·(101325 / 6894.757293168) psi",
            listOf("x — a quantity in atmospheres (atm)"),
            "the equivalent value in pounds per square inch (psi)"
        ),
        "atm_to_torr" to d(
            "Unit conversion from atmospheres (atm) to torr (mmHg). Formula: 1 atm = x·760 torr",
            listOf("x — a quantity in atmospheres (atm)"),
            "the equivalent value in torr (mmHg)"
        ),
        "atom_economy" to d(
            "atom economy: mass of desired product ÷ total reactant mass. Define: atom_economy(mw_desired, mw_reactants) = (mw_desired / mw_reactants)·100",
            listOf("mw_desired — molar mass of the desired product (g/mol)", "mw_reactants — summed molar masses of all reactants (g/mol)"),
            "a percentage ≤ 100"
        ),
        "avg_speed_2" to d(
            "average speed over two equal-distance legs (harmonic mean). Define: avg_speed_2(s1, s2) = 2s1·s2 / (s1 + s2)",
            listOf("s1 — speed on the first leg (m/s)", "s2 — speed on the second leg (m/s)"),
            "an average speed in the inputs’ speed unit"
        ),
        "banking_angle_deg" to d(
            "frictionless banking angle for speed v on radius r. Define: banking_angle_deg(vel, r) = rad2deg(atan((vel^2) / (r·g0)))",
            listOf("vel — vehicle speed (m/s)", "r — bend radius (m)"),
            "a banking angle in degrees (°)"
        ),
        "bar_to_Pa" to d(
            "Unit conversion from bar to pascals (Pa). Formula: 1 bar = x·100000 Pa",
            listOf("x — a quantity in bar"),
            "the equivalent value in pascals (Pa)"
        ),
        "bar_to_atm" to d(
            "Unit conversion from bar to atmospheres (atm). Formula: 1 bar = x / 1.01325 atm",
            listOf("x — a quantity in bar"),
            "the equivalent value in atmospheres (atm)"
        ),
        "beat_freq" to d(
            "beat frequency of two close tones |f₁ − f₂|. Define: beat_freq(f1, f2) = @abs(f1 - f2)",
            listOf("f1 — first tone frequency (Hz)", "f2 — second tone frequency (Hz)"),
            "a beat frequency in hertz (Hz)"
        ),
        "beta_fn" to d(
            "Euler beta function B(x, y) built on Gamma. Define: beta_fn(a, b) = @exp(@lgamma(a) + @lgamma(b) - @lgamma(a + b))",
            listOf("x — first shape argument", "y — second shape argument"),
            "the beta-function value (positive for positive arguments)"
        ),
        "bit_and" to d(
            "bitwise AND of two non-negative integers. Define: bit_and(a, b) = @band(a, b)",
            listOf("a — first non-negative integer", "b — second non-negative integer (each: trailing `none` placeholder in the declaration is optional)"),
            "a non-negative integer"
        ),
        "bit_len" to d(
            "number of bits needed to represent a non-negative integer. Define: bit_len(n) as a block: if (n < 0 || n != @floor(n)) { error(\"bit_len() requires a non-negative integer\") } -> b := 0 -> x := n -> (x > 0) ?? b += 1 -> x = @floor(x / 2) -> b",
            listOf("n — non-negative integer (trailing `none` placeholder is optional)"),
            "the bit count (positive integer)"
        ),
        "bit_not" to d(
            "bitwise NOT (ones-complement) of a non-negative integer. Define: bit_not(a) = @bnot(a)",
            listOf("a — non-negative integer to complement (trailing `none` placeholder is optional)"),
            "a non-negative integer (ones-complement)"
        ),
        "bit_or" to d(
            "bitwise OR of two non-negative integers. Define: bit_or(a, b) = @bor(a, b)",
            listOf("a — first non-negative integer", "b — second non-negative integer (each: trailing `none` placeholder in the declaration is optional)"),
            "a non-negative integer"
        ),
        "bit_shl" to d(
            "bitwise left shift a << b. Define: bit_shl(a, b) = @bshl(a, b)",
            listOf("a — value to shift (non-negative integer)", "b — number of bit positions (non-negative integer; trailing `none` placeholder is optional)"),
            "a non-negative integer"
        ),
        "bit_shr" to d(
            "bitwise right shift a >> b. Define: bit_shr(a, b) = @bshr(a, b)",
            listOf("a — value to shift (non-negative integer)", "b — number of bit positions (non-negative integer; trailing `none` placeholder is optional)"),
            "a non-negative integer"
        ),
        "bit_xor" to d(
            "bitwise XOR of two non-negative integers. Define: bit_xor(a, b) = @bxor(a, b)",
            listOf("a — first non-negative integer", "b — second non-negative integer (each: trailing `none` placeholder in the declaration is optional)"),
            "a non-negative integer"
        ),
        "boat_downstream" to d(
            "boat speed along a stream = still-water speed + stream speed. Define: boat_downstream(u_boat, v_stream) = u_boat + v_stream",
            listOf("u_boat — still-water boat speed", "v_stream — stream speed"),
            "an effective speed in the inputs’ speed unit"
        ),
        "boat_still_speed" to d(
            "still-water speed recovered from upstream and downstream speeds. Define: boat_still_speed(d_spd, u_spd) = (d_spd + u_spd) / 2",
            listOf("d_spd — downstream (with-current) speed", "u_spd — upstream (against-current) speed"),
            "a speed in the inputs’ speed unit"
        ),
        "boat_upstream" to d(
            "boat speed against a stream = still-water speed − stream speed. Define: boat_upstream(u_boat, v_stream) = u_boat - v_stream",
            listOf("u_boat — still-water boat speed", "v_stream — stream speed"),
            "an effective speed in the inputs’ speed unit"
        ),
        "bohr_energy_ev" to d(
            "hydrogen Bohr level energy in electron-volts. Define: bohr_energy_ev(n_orbit, z_atomic) = -13.6·((z_atomic^2) / (n_orbit^2)). Default z atomic = 1",
            listOf("n_orbit — orbit index n (1, 2, 3, …)", "z_atomic — atomic number Z (1 for hydrogen)"),
            "level energy in electron-volts (eV, negative for bound states)"
        ),
        "bohr_radius_n" to d(
            "Bohr radius of the nth hydrogen orbit. Define: bohr_radius_n(n_orbit) = 5.29177210903·(10^-11)·(n_orbit^2)",
            listOf("n_orbit — orbit index n (1, 2, 3, …)"),
            "orbital radius in metres (m)"
        ),
        "boyle_p2" to d(
            "Boyle law P₁V₁ = P₂V₂ solved for the second pressure. Define: boyle_p2(p1, v1, v2) = (p1·v1) / v2",
            listOf("p1 — initial pressure", "v1 — initial volume", "v2 — final volume"),
            "a pressure in the units of P₁"
        ),
        "bp_elevation" to d(
            "boiling-point elevation ΔT_b = K_b·m (colligative). Define: bp_elevation(i_vant, kb, molality_m) = i_vant·kb·molality_m",
            listOf("i_vant — van’t Hoff factor i", "kb — molal boiling-point elevation constant (K·kg/mol)", "molality_m — molality (mol/kg)"),
            "a temperature shift ΔT_b in °C or K"
        ),
        "bragg_d_spacing" to d(
            "crystal plane spacing from Bragg diffraction nλ = 2d·sinθ. Define: bragg_d_spacing(n_order, wavelength, theta_deg) = (n_order·wavelength) / (2·sin(deg2rad(theta_deg)))",
            listOf("n_order — diffraction order n", "wavelength — X-ray wavelength λ", "theta_deg — glancing angle θ (°)"),
            "a plane spacing in metres (m)"
        ),
        "brewster_angle" to d(
            "Brewster (polarising) angle θ_B = atan(n₂/n₁). Define: brewster_angle(n1, n2) = rad2deg(atan(n2 / n1))",
            listOf("n1 — incidence-side refractive index", "n2 — transmission-side refractive index"),
            "an angle in degrees (°)"
        ),
        "buoyant_force" to d(
            "Archimedes buoyant force F = ρ_fluid·g·V. Define: buoyant_force(rho_fluid, vol_sub) = rho_fluid·g0·vol_sub",
            listOf("rho_fluid — fluid density (kg/m³)", "vol_sub — displaced fluid volume V (m³)"),
            "a force in newtons (N)"
        ),
        "c_to_k" to d(
            "Unit conversion from degrees Celsius (°C) to kelvin (K). Formula: 1 c = c + 273.15 k",
            listOf("c — a quantity in degrees Celsius (°C)"),
            "the equivalent value in kelvin (K)"
        ),
        "cal_to_J" to d(
            "Unit conversion from calories (cal) to joules (J). Formula: 1 cal = x·4.184 J",
            listOf("x — a quantity in calories (cal)"),
            "the equivalent value in joules (J)"
        ),
        "calc_snap" to d(
            "snaps a value onto unit-preserving noise (stable rounding for lazy calculus). Define: calc_snap(res, tol) as a block: r := raw_val(res) -> n := @floor(r + 0.5) -> s := (@abs(r - n) < tol) ? n : ((@abs(r) < tol·0.1) ? 0 : r) -> with_unit(s, res)",
            listOf("x — value to snap", "step — snapping step size"),
            "the snapped value on the stable grid"
        ),
        "cap_energy" to d(
            "energy stored in a capacitor U = ½CV². Define: cap_energy(cap_c, volt_v) = ½·cap_c·(volt_v^2)",
            listOf("cap_c — capacitance C (F)", "volt_v — voltage across the capacitor V (V)"),
            "an energy in joules (J)"
        ),
        "cap_parallel" to d(
            "equivalent capacitance of a parallel pair C = C₁ + C₂. Define: cap_parallel(c1, c2) = c1 + c2",
            listOf("c1 — first capacitance (F)", "c2 — second capacitance (F)"),
            "a capacitance in farads (F)"
        ),
        "cap_series" to d(
            "equivalent capacitance of a series pair 1/C = 1/C₁ + 1/C₂. Define: cap_series(c1, c2) = (c1·c2) / (c1 + c2)",
            listOf("c1 — first capacitance (F)", "c2 — second capacitance (F)"),
            "a capacitance in farads (F)"
        ),
        "capacitive_reactance" to d(
            "capacitive reactance X_C = 1/(2πfC). Define: capacitive_reactance(freq_hz, cap_c) = 1 / (2π·freq_hz·cap_c)",
            listOf("freq hz — frequency (Hz)", "cap c — °C"),
            "a reactance in ohms (Ω)"
        ),
        "cardiac_output" to d(
            "cardiac output CO = stroke volume × heart rate. Define: cardiac_output(hr_bpm, stroke_vol_ml) = (hr_bpm·stroke_vol_ml) / 1000",
            listOf("hr_bpm — heart rate (beats/min)", "stroke_vol_ml — stroke volume (mL)"),
            "an output in mL/min"
        ),
        "carnot_eff" to d(
            "Carnot efficiency of a heat engine between two reservoirs. Define: carnot_eff(t_cold_k, t_hot_k) = 1 - (t_cold_k / t_hot_k)",
            listOf("t cold k — temperature (K)", "t hot k — temperature (K)"),
            "dimensionless efficiency η < 1"
        ),
        "cbrt" to d(
            "real cube root (handles negative operands). Define: cbrt(x) = @cbrt(x)",
            listOf("x — real argument (negative allowed, yields negative root)"),
            "a real number (signed root)"
        ),
        "ceil" to d(
            "rounding up to the next whole number. Define: ceil(x) = @ceil(x)",
            listOf("x — value to round up"),
            "a real number rounded up"
        ),
        "cell_emf" to d(
            "standard cell potential E°cell = E°cathode − E°anode. Define: cell_emf(e_cathode, e_anode) = e_cathode - e_anode",
            listOf("e_cathode — cathode (reduction) standard potential (V)", "e_anode — anode (oxidation) standard potential (V)"),
            "a cell potential in volts (V)"
        ),
        "cell_surface_sphere" to d(
            "surface area of a spherical cell. Define: cell_surface_sphere(r) = 4π·(r^2)",
            listOf("r — cell radius (m)"),
            "an area in the input length unit squared"
        ),
        "cell_volume_sphere" to d(
            "volume of a spherical cell. Define: cell_volume_sphere(r) = (4 / 3)·π·(r^3)",
            listOf("r — cell radius (m)"),
            "a volume in the input length unit cubed"
        ),
        "centripetal_acc" to d(
            "centripetal acceleration a = v²/r. Define: centripetal_acc(vel, r) = (vel^2) / r",
            listOf("vel — tangential speed (m/s)", "r — circular path radius (m)"),
            "an acceleration in m/s²"
        ),
        "centripetal_force" to d(
            "centripetal force F = m·v²/r. Define: centripetal_force(mass, vel, r) = mass·(vel^2) / r",
            listOf("mass — mass of the body (kg)", "vel — tangential speed (m/s)", "r — circular path radius (m)"),
            "a force in newtons (N)"
        ),
        "chargaff_c_from_t" to d(
            "Chargaff rule: cytosine count from thymine count (C = T in dsDNA). Define: chargaff_c_from_t(t_pct) = 50 - t_pct",
            listOf("t_pct — thymine percentage of the DNA sample"),
            "a base count (equal to the T-derived complement)"
        ),
        "chargaff_g_from_a" to d(
            "Chargaff rule: guanine count from adenine count (G = A in dsDNA). Define: chargaff_g_from_a(a_pct) = 50 - a_pct",
            listOf("a_pct — adenine percentage of the DNA sample"),
            "a base count (equal to the A-derived complement)"
        ),
        "charles_v2" to d(
            "Charles law V₁/T₁ = V₂/T₂ solved for the second volume. Define: charles_v2(v1, t1_k, t2_k) = (v1·t2_k) / t1_k",
            listOf("v1 — initial volume", "t1_k — initial temperature (K)", "t2_k — final temperature (K)"),
            "a volume in the units of V₁"
        ),
        "chi_sq_term" to d(
            "one cell term (O−E)²/E of a chi-square statistic. Define: chi_sq_term(observed, expected) = ((observed - expected)^2) / expected",
            listOf("observed — observed count O", "expected — expected count E"),
            "a single χ² contribution (non-negative)"
        ),
        "ci_amount_n" to d(
            "Regarding Simple & Compound Interest, Depreciation & Loan EMI. Define: ci_amount_n(p, r_pct, t_yr, n_per_yr) = p·((1 + (r_pct / 100) / n_per_yr)^(n_per_yr·t_yr))",
            listOf("p — principal", "r_pct — annual rate (%)", "t_yr — time (years)", "n_per_yr — compounding periods per year"),
            "an amount of substance in moles (mol)"
        ),
        "ci_si_diff_2yr" to d(
            "Regarding Simple & Compound Interest, Depreciation & Loan EMI. Define: ci_si_diff_2yr(p, r_pct) = p·((r_pct / 100)^2)",
            listOf("p — principal", "r_pct — annual rate (%)"),
            "the compound−simple interest difference over 2 years"
        ),
        "cm_to_in" to d(
            "Unit conversion from centimetres (cm) to inches (in). Formula: 1 cm = cm_v / 2.54 in",
            listOf("cm_v — a quantity in centimetres (cm)"),
            "the equivalent value in inches (in)"
        ),
        "cm_to_km" to d(
            "Unit conversion from centimetres (cm) to kilometres (km). Formula: 1 cm = x / 100000 km",
            listOf("x — a quantity in centimetres (cm)"),
            "the equivalent value in kilometres (km)"
        ),
        "cm_to_m" to d(
            "Unit conversion from centimetres (cm) to metres (m). Formula: 1 cm = x·0.01 m",
            listOf("x — a quantity in centimetres (cm)"),
            "the equivalent value in metres (m)"
        ),
        "cm_to_mm" to d(
            "Unit conversion from centimetres (cm) to millimetres (mm). Formula: 1 cm = x·10 mm",
            listOf("x — a quantity in centimetres (cm)"),
            "the equivalent value in millimetres (mm)"
        ),
        "codons_for_protein" to d(
            "codon count for an amino-acid chain length. Define: codons_for_protein(amino_acids) = amino_acids + 1",
            listOf("amino_acids — amino-acid count (per chain of one protein)"),
            "a codon count"
        ),
        "combined_gas_v2" to d(
            "combined gas law P₁V₁/T₁ = P₂V₂/T₂ solved for V₂. Define: combined_gas_v2(p1, v1, t1_k, p2, t2_k) = (p1·v1·t2_k) / (p2·t1_k)",
            listOf("p1 — initial pressure", "v1 — initial volume", "t1_k — initial temperature (K)", "p2 — final pressure", "t2_k — final temperature (K)"),
            "a volume in the units of V₁"
        ),
        "competitive_inh_v" to d(
            "velocity with a competitive inhibitor (Km effectively raised). Define: competitive_inh_v(vmax, km, substrate_s, inh_i, ki) = (vmax·substrate_s) / (km·(1 + inh_i / ki) + substrate_s)",
            listOf("vmax — maximum velocity", "km — Michaelis constant", "substrate_s — substrate concentration [S]", "inh_i — inhibitor concentration [I]", "ki — inhibition constant"),
            "a reaction velocity in the unit of Vmax"
        ),
        "compound_amount" to d(
            "compound-interest amount A = P·(1 + r)^t. Define: compound_amount(p, r_pct, t_yr) = p·((1 + r_pct / 100)^t_yr)",
            listOf("p — principal", "r_pct — rate per period (%)", "t_yr — number of periods (typically years)"),
            "the accumulated amount in the same currency unit"
        ),
        "continuity_v2" to d(
            "Regarding Fluid Mechanics, Elasticity & Surface Tension. Define: continuity_v2(a1, v1, a2) = (a1·v1) / a2",
            listOf("a1 — cross-sectional area at point 1 (m²)", "v1 — velocity at point 1 (m/s)", "a2 — cross-sectional area at point 2 (m²)"),
            "the outflow velocity in m/s"
        ),
        "cos" to d(
            "cosine of x (radians; honours the deg/rad mode). Define: cos(x) = @cos(x)",
            listOf("x — argument in radians (the calculator’s deg/rad mode also honours tagged values)"),
            "a value in [−1, 1]"
        ),
        "cosec" to d(
            "cosecant = 1/sin(x). Define: cosec(x) = csc(x)",
            listOf("x — argument in radians (the calculator’s deg/rad mode also honours tagged values)"),
            "a real number (|value| ≥ 1 outside rounding)"
        ),
        "cosh" to d(
            "hyperbolic cosine (e^x + e^−x)/2. Define: cosh(x) = ½·(@exp(x) + @exp(-x))",
            listOf("x — real argument"),
            "a value in [1, ∞)"
        ),
        "cot" to d(
            "cotangent = 1/tan(x). Define: cot(x) = @cos(x) / @sin(x)",
            listOf("x — argument in radians (the calculator’s deg/rad mode also honours tagged values)"),
            "a real number"
        ),
        "coulomb_force" to d(
            "electrostatic force between point charges F = k·q₁q₂/r². Define: coulomb_force(q1, q2, r) = ke_coulomb·q1·q2 / (r^2)",
            listOf("q1 — first charge (C)", "q2 — second charge (C)", "r — separation distance (m)"),
            "a force in newtons (N)"
        ),
        "cp_from_loss_pct" to d(
            "cost price recovered from a selling price and loss percentage. Define: cp_from_loss_pct(sp, l_pct) = sp / (1 - l_pct / 100)",
            listOf("sp — selling price", "l_pct — loss percentage"),
            "a dimensionless ratio/percentage"
        ),
        "cp_from_profit_pct" to d(
            "cost price recovered from a selling price and profit percentage. Define: cp_from_profit_pct(sp, p_pct) = sp / (1 + p_pct / 100)",
            listOf("sp — selling price", "p_pct — profit percentage"),
            "a dimensionless ratio/percentage"
        ),
        "crystal_density" to d(
            "crystal density from unit-cell mass and volume. Define: crystal_density(z_atoms, molar_mass, a_edge_cm) = (z_atoms·molar_mass) / ((a_edge_cm^3)·N_A)",
            listOf("z_atoms — formula units per unit cell (Z)", "molar_mass — molar mass (g/mol)", "a_edge_cm — unit-cell edge length (cm)"),
            "a density in kg/m³"
        ),
        "csc" to d(
            "Regarding Circular Trigonometric Functions. Define: csc(x) = 1 / @sin(x)",
            listOf("x — argument in radians (the calculator’s deg/rad mode also honours tagged values)"),
            "a real number (|value| ≥ 1 outside rounding)"
        ),
        "cyclotron_radius" to d(
            "cyclotron radius r = mv/(qB) of a charged particle in a magnetic field. Define: cyclotron_radius(mass, vel, q, b_field) = (mass·vel) / (q·b_field)",
            listOf("mass — mass of the particle (kg)", "vel — particle speed (m/s)", "q — particle charge (C)", "b_field — magnetic field strength B (T)"),
            "a radius in metres (m)"
        ),
        "dalton_partial_p" to d(
            "partial pressure via Dalton law P = χ·P_total. Define: dalton_partial_p(mole_frac, p_total) = mole_frac·p_total",
            listOf("mole_frac — mole fraction of the gas in the mixture", "p_total — total pressure of the mixture"),
            "a pressure in pascals (Pa)"
        ),
        "day_to_hr" to d(
            "Unit conversion from days to hours. Formula: 1 day = x·24 hr",
            listOf("x — a quantity in days"),
            "the equivalent value in hours"
        ),
        "day_to_sec" to d(
            "Unit conversion from days to seconds. Formula: 1 day = x·86400 sec",
            listOf("x — a quantity in days"),
            "the equivalent value in seconds"
        ),
        "de_broglie" to d(
            "de Broglie matter-wave wavelength λ = h/p. Define: de_broglie(mass, vel) = h_planck / (mass·vel)",
            listOf("mass — mass of the particle (kg)", "vel — particle speed (m/s)"),
            "a wavelength in metres (m)"
        ),
        "decay_rem" to d(
            "remaining amount after first-order decay N = N₀e^−λt. Define: decay_rem(n0, time_t, half_life) = n0·(0.5^(time_t / half_life))",
            listOf("n0 — initial amount N₀", "time_t — elapsed time (same unit as t half-life)", "half_life — half-life duration"),
            "the remaining amount in the same unit as N₀"
        ),
        "deg_to_arcmin" to d(
            "Unit conversion from degrees (°) to arcminutes (′). Formula: 1 deg = d·60 arcmin",
            listOf("d — a quantity in degrees (°)"),
            "the equivalent value in arcminutes (′)"
        ),
        "deg_to_arcsec" to d(
            "Unit conversion from degrees (°) to arcseconds (″). Formula: 1 deg = d·3600 arcsec",
            listOf("d — a quantity in degrees (°)"),
            "the equivalent value in arcseconds (″)"
        ),
        "deg_to_rad" to d(
            "Unit conversion from degrees (°) to radians (rad). Formula: 1 deg = d·π / 180 rad",
            listOf("d — a quantity in degrees (°)"),
            "the equivalent value in radians (rad)"
        ),
        "depreciated_val" to d(
            "value after straight-line depreciation. Define: depreciated_val(p0, r_pct, t_yr) = p0·((1 - r_pct / 100)^t_yr)",
            listOf("p0 — original value", "r_pct — annual depreciation rate (%)", "t_yr — years elapsed"),
            "the depreciated value in the same currency unit"
        ),
        "diffusion_pressure_deficit" to d(
            "DPD = osmotic pressure − turgor pressure. Define: diffusion_pressure_deficit(osmotic_p, turgor_p) = osmotic_p - turgor_p",
            listOf("osmotic_p — cell osmotic pressure", "turgor_p — cell turgor pressure"),
            "a pressure in the inputs’ pressure unit"
        ),
        "dilution_v1" to d(
            "initial volume in a dilution M₁V₁ = M₂V₂. Define: dilution_v1(c1, c2, v2) = (c2·v2) / c1",
            listOf("c1 — initial concentration", "c2 — target concentration", "v2 — final volume"),
            "a volume in the units of the input volumes"
        ),
        "discount_amt" to d(
            "discount amount for a price at a discount percentage. Define: discount_amt(marked_p, d_pct) = marked_p·(d_pct / 100)",
            listOf("marked_p — marked (list) price", "d_pct — discount percentage"),
            "the discount in the same currency unit as the price"
        ),
        "dna_length_nm" to d(
            "DNA double-helix length in nanometres (3.4 nm per 10 bp). Define: dna_length_nm(base_pairs) = base_pairs·0.34",
            listOf("base_pairs — number of base pairs"),
            "a helix length in nanometres (nm)"
        ),
        "dna_turns" to d(
            "number of helical turns in B-DNA (10 bp per turn). Define: dna_turns(base_pairs) = base_pairs / 10",
            listOf("base_pairs — number of base pairs"),
            "a turn count (rounded real)"
        ),
        "dna_weight_da" to d(
            "approximate mass of a DNA segment in daltons (≈660 Da per bp). Define: dna_weight_da(base_pairs) = base_pairs·650",
            listOf("base_pairs — number of base pairs"),
            "a mass in daltons (Da)"
        ),
        "doppler_freq" to d(
            "observed frequency under the Doppler effect. Define: doppler_freq(f0, v_sound, v_obs, v_src) = f0·((v_sound + v_obs) / (v_sound - v_src))",
            listOf("f0 — emitted frequency (Hz)", "v_sound — wave speed in the medium (m/s)", "v_obs — observer velocity, + towards the source (m/s)", "v_src — source velocity, + towards the observer (m/s)"),
            "an observed frequency in hertz (Hz)"
        ),
        "drift_velocity" to d(
            "electron drift velocity in a conductor I = n·A·q·v_d. Define: drift_velocity(current_i, n_density, area, q_charge) = current_i / (n_density·area·q_charge)",
            listOf("current_i — current (A)", "n_density — free-electron density (m⁻³)", "area — cross-section area (m²)", "q_charge — carrier charge (C)"),
            "a drift speed in m/s"
        ),
        "dyn_to_N" to d(
            "Unit conversion from dynes (dyn) to newtons (N). Formula: 1 dyn = x / 100000 N",
            listOf("x — a quantity in dynes (dyn)"),
            "the equivalent value in newtons (N)"
        ),
        "ejection_fraction" to d(
            "ejection fraction: fraction of EDV ejected per beat. Define: ejection_fraction(edv_ml, esv_ml) = ((edv_ml - esv_ml) / edv_ml)·100",
            listOf("edv_ml — end-diastolic volume (mL)", "esv_ml — end-systolic volume (mL)"),
            "dimensionless fraction of EDV"
        ),
        "elastic_pe" to d(
            "elastic potential energy stored in a spring ½kx². Define: elastic_pe(k_spring, disp) = ½·k_spring·(disp^2)",
            listOf("k_spring — spring constant k (N/m)", "disp — displacement from equilibrium x (m)"),
            "an energy in joules (J)"
        ),
        "elastic_v1" to d(
            "post-collision velocity of mass 1 (1D elastic collision). Define: elastic_v1(m1, u1, m2, u2) = ((m1 - m2)·u1 + 2·m2·u2) / (m1 + m2)",
            listOf("m1 — first mass (kg)", "u1 — velocity of mass 1 before impact (m/s)", "m2 — second mass (kg)", "u2 — velocity of mass 2 before impact (m/s)"),
            "a velocity in m/s"
        ),
        "elastic_v2" to d(
            "post-collision velocity of mass 2 (1D elastic collision). Define: elastic_v2(m1, u1, m2, u2) = ((m2 - m1)·u2 + 2·m1·u1) / (m1 + m2)",
            listOf("m1 — first mass (kg)", "u1 — velocity of mass 1 before impact (m/s)", "m2 — second mass (kg)", "u2 — velocity of mass 2 before impact (m/s)"),
            "a velocity in m/s"
        ),
        "elec_power" to d(
            "electrical power dissipated in a resistance P = I²R. Define: elec_power(volt_v, current_i) = volt_v·current_i",
            listOf("volt_v — potential difference V (V)", "current_i — current I (A)"),
            "a power in watts (W)"
        ),
        "enthalpy_rxn" to d(
            "reaction enthalpy ΔH = ΣH_products − ΣH_reactants. Define: enthalpy_rxn(sum_h_prod, sum_h_react) = sum_h_prod - sum_h_react",
            listOf("sum_h_prod — summed product enthalpies", "sum_h_react — summed reactant enthalpies"),
            "an enthalpy change in kJ/mol when inputs are in kJ/mol"
        ),
        "enzyme_catalytic_eff" to d(
            "enzyme catalytic efficiency kcat/Km. Define: enzyme_catalytic_eff(kcat, km) = kcat / km",
            listOf("kcat — turnover number kcat (s⁻¹)", "km — Michaelis constant"),
            "catalytic efficiency (per-second per-molar units)"
        ),
        "erfc" to d(
            "complementary error function 1 − erf(x), Gaussian-tail integral. Define: erfc(x) = 1 - @erf(x)",
            listOf("x — argument of the Gaussian-tail integral"),
            "a value in (0, 2)"
        ),
        "escape_vel" to d(
            "escape velocity from a body v = √(2GM/R). Define: escape_vel(mass_body, r) = √(2·G_grav·mass_body / r)",
            listOf("mass_body — mass of the body (kg)", "r — body radius (m)"),
            "a speed in m/s"
        ),
        "exp" to d(
            "natural exponential e^x. Define: exp(x) = @exp(x)",
            listOf("x — exponent of the natural base e"),
            "a positive real number e^x"
        ),
        "exp_cdf" to d(
            "exponential distribution cumulative probability up to x. Define: exp_cdf(x, lam) = (x < 0) ? 0 : 1 - @exp(-lam·x)",
            listOf("x — point on the axis", "lambda — rate λ = 1/mean"),
            "a probability in [0, 1]"
        ),
        "exp_pdf" to d(
            "exponential distribution probability density at x. Define: exp_pdf(x, lam) = (x < 0) ? 0 : lam·@exp(-lam·x)",
            listOf("x — point on the axis", "lambda — rate λ = 1/mean"),
            "a density value ≥ 0"
        ),
        "f_to_k" to d(
            "Unit conversion from degrees Fahrenheit (°F) to kelvin (K). Formula: 1 f = (f - 32) / 1.8 + 273.15 k",
            listOf("f — a quantity in degrees Fahrenheit (°F)"),
            "the equivalent value in kelvin (K)"
        ),
        "faraday_mass" to d(
            "mass deposited in electrolysis m = Q·M/(n·F). Define: faraday_mass(current_i, time_s, molar_mass, n_elec) = (current_i·time_s·molar_mass) / (n_elec·F_faraday)",
            listOf("current_i — current (A)", "time_s — duration (s)", "molar_mass — molar mass (g/mol)", "n_elec — electrons per formula unit"),
            "a deposited mass in grams (g)"
        ),
        "floor" to d(
            "rounding down to the previous whole number. Define: floor(x) = @floor(x)",
            listOf("x — value to round down"),
            "a real number rounded down"
        ),
        "fourth_proportional" to d(
            "fourth proportional d of a:b::c:d, d = b·c/a. Define: fourth_proportional(a, b, c) = (b·c) / a",
            listOf("a — first term", "b — second term", "c — third term"),
            "the fourth proportional value"
        ),
        "fov_diameter" to d(
            "field-of-view diameter at a second magnification. Define: fov_diameter(fov_low, mag_low, mag_high) = (fov_low·mag_low) / mag_high",
            listOf("fov_low — field width at the lower magnification (µm)", "mag_low — lower magnification", "mag_high — higher magnification"),
            "a field width in the input’s length unit (typically µm)"
        ),
        "fp_depression" to d(
            "freezing-point depression ΔT_f = K_f·m (colligative). Define: fp_depression(i_vant, kf, molality_m) = i_vant·kf·molality_m",
            listOf("i_vant — van’t Hoff factor i", "kf — molal freezing-point constant (K·kg/mol)", "molality_m — molality (mol/kg)"),
            "a temperature shift ΔT_f in °C or K"
        ),
        "fract" to d(
            "fractional part of x (x − trunc(x)). Define: fract(x) = x - @floor(x)",
            listOf("x — value whose fractional part is wanted"),
            "the fractional part in [0, 1)"
        ),
        "friction_force" to d(
            "friction force f = μ·N. Define: friction_force(mu, normal_n) = mu·normal_n",
            listOf("mu — coefficient of friction μ (dimensionless)", "normal_n — normal reaction force N (N)"),
            "a force in newtons (N)"
        ),
        "ft_to_in" to d(
            "Unit conversion from feet (ft) to inches (in). Formula: 1 ft = x·12 in",
            listOf("x — a quantity in feet (ft)"),
            "the equivalent value in inches (in)"
        ),
        "ft_to_m" to d(
            "Unit conversion from feet (ft) to metres (m). Formula: 1 ft = ft_v·0.3048 m",
            listOf("ft_v — a quantity in feet (ft)"),
            "the equivalent value in metres (m)"
        ),
        "g_to_kg" to d(
            "Unit conversion from grams (g) to kilograms (kg). Formula: 1 g = x·0.001 kg",
            listOf("x — a quantity in grams (g)"),
            "the equivalent value in kilograms (kg)"
        ),
        "g_to_mg" to d(
            "Unit conversion from grams (g) to milligrams (mg). Formula: 1 g = x·1000 mg",
            listOf("x — a quantity in grams (g)"),
            "the equivalent value in milligrams (mg)"
        ),
        "g_to_oz" to d(
            "Unit conversion from grams (g) to ounces (oz). Formula: 1 g = x / 28.349523125 oz",
            listOf("x — a quantity in grams (g)"),
            "the equivalent value in ounces (oz)"
        ),
        "gal_to_L" to d(
            "Unit conversion from US gallons (gal) to litres (L). Formula: 1 gal = x·3.785411784 L",
            listOf("x — a quantity in US gallons (gal)"),
            "the equivalent value in litres (L)"
        ),
        "gcd" to d(
            "greatest common divisor via the Euclid algorithm. Define: gcd(a, b) as a block: x := @abs(a) -> y := @abs(b) -> (y > 0) ?? t := x % y -> x = y -> y = t -> x",
            listOf("a — first integer", "b — second integer"),
            "the greatest common divisor (positive integer)"
        ),
        "geom_pmf" to d(
            "geometric distribution: P(first success on trial k). Define: geom_pmf(k, p) = ((1 - p)^(k - 1))·p",
            listOf("k — trial index of first success (1, 2, …)", "p — per-trial success probability"),
            "a probability in (0, 1]"
        ),
        "gibbs_electro" to d(
            "Gibbs free energy from cell potential ΔG = −nFE°. Define: gibbs_electro(n_elec, e_cell_v) = -n_elec·F_faraday·e_cell_v",
            listOf("n_elec — electrons transferred per reaction", "e_cell_v — standard cell potential (V)"),
            "a Gibbs energy in joules (J, negative for spontaneous cells)"
        ),
        "gibbs_free_energy" to d(
            "Gibbs free energy ΔG = ΔH − TΔS. Define: gibbs_free_energy(dh_j, temp_k, ds_j_k) = dh_j - temp_k·ds_j_k",
            listOf("dh j — energy (J)", "temp k — temperature (K)", "ds j k — temperature (K)"),
            "a Gibbs energy consistent with the unit set of the inputs"
        ),
        "gibbs_from_keq" to d(
            "Gibbs free energy from an equilibrium constant ΔG° = −RT·lnK. Define: gibbs_from_keq(temp_k, keq) = -R_gas·temp_k·ln(keq)",
            listOf("temp_k — absolute temperature (K)", "keq — thermodynamic equilibrium constant K"),
            "a standard Gibbs energy in J/mol"
        ),
        "gp_inf_sum" to d(
            "sum of an infinite GP with |r| < 1, a/(1−r). Define: gp_inf_sum(a, r) = a / (1 - r)",
            listOf("a — first term a₁", "r — common ratio with |r| < 1"),
            "the infinite-series sum (finite only when |r| < 1)"
        ),
        "gp_nth" to d(
            "nth term of a geometric progression aₙ = a·rⁿ⁻¹. Define: gp_nth(a, r, n) = a·(r^(n - 1))",
            listOf("a — first term a₁", "r — common ratio r", "n — term index (1-based)"),
            "the nth term value"
        ),
        "graham_rate_ratio" to d(
            "effusion-rate ratio of two gases via Graham law. Define: graham_rate_ratio(molar_mass1, molar_mass2) = √(molar_mass2 / molar_mass1)",
            listOf("molar_mass1 — molar mass of gas 1 (g/mol)", "molar_mass2 — molar mass of gas 2 (g/mol)"),
            "a dimensionless ratio/percentage"
        ),
        "grav_force" to d(
            "Newtonian gravitational attraction F = G·m₁m₂/r². Define: grav_force(m1, m2, r) = G_grav·m1·m2 / (r^2)",
            listOf("m1 — first mass (kg)", "m2 — second mass (kg)", "r — separation distance between centres (m)"),
            "a force in newtons (N)"
        ),
        "grav_pe" to d(
            "general gravitational potential energy U = −G·m₁m₂/r. Define: grav_pe(m1, m2, r) = -G_grav·m1·m2 / r",
            listOf("m1 — first mass (kg)", "m2 — second mass (kg)", "r — separation distance between centres (m)"),
            "a potential energy in joules (J; negative for bound systems)"
        ),
        "h_from_ph" to d(
            "hydronium concentration from pH: [H⁺] = 10^−pH. Define: h_from_ph(ph_val) = 10^(-ph_val)",
            listOf("ph_val — the pH of the solution"),
            "a hydronium concentration in mol/L"
        ),
        "half_life_1st" to d(
            "half-life of a first-order decay t½ = ln2/k. Define: half_life_1st(rate_k) = ln(2) / rate_k",
            listOf("rate k — temperature (K)"),
            "a half-life in the inverse of k’s time unit"
        ),
        "henderson_hasselbalch" to d(
            "buffer pH via the Henderson-Hasselbalch equation. Define: henderson_hasselbalch(pka, conj_base, weak_acid) = pka + log₁₀(conj_base / weak_acid)",
            listOf("pka — the buffer acid’s pKa", "conj_base — conjugate-base concentration ratio term", "weak_acid — weak-acid concentration term"),
            "buffer pH (dimensionless)"
        ),
        "hooke_force" to d(
            "Hooke-law spring force magnitude kx. Define: hooke_force(k_spring, disp) = -k_spring·disp",
            listOf("k_spring — spring constant k (N/m)", "disp — displacement from equilibrium x (m)"),
            "a force in newtons (N)"
        ),
        "hp_nth" to d(
            "nth term of a harmonic progression (reciprocal AP). Define: hp_nth(a, d, n) = 1 / (a + (n - 1)·d)",
            listOf("a — first term a₁", "d — common difference of the inverted AP", "n — term index (1-based)"),
            "the nth term value"
        ),
        "hp_to_W" to d(
            "Unit conversion from mechanical horsepower (hp) to watts (W). Formula: 1 hp = x·745.699872 W",
            listOf("x — a quantity in mechanical horsepower (hp)"),
            "the equivalent value in watts (W)"
        ),
        "hp_to_kW" to d(
            "Unit conversion from mechanical horsepower (hp) to kilowatts (kW). Formula: 1 hp = x·0.745699872 kW",
            listOf("x — a quantity in mechanical horsepower (hp)"),
            "the equivalent value in kilowatts (kW)"
        ),
        "hr_to_day" to d(
            "Unit conversion from hours to days. Formula: 1 hr = x / 24 day",
            listOf("x — a quantity in hours"),
            "the equivalent value in days"
        ),
        "hr_to_min" to d(
            "Unit conversion from hours to minutes. Formula: 1 hr = x·60 min",
            listOf("x — a quantity in hours"),
            "the equivalent value in minutes"
        ),
        "hr_to_sec" to d(
            "Unit conversion from hours to seconds. Formula: 1 hr = x·3600 sec",
            listOf("x — a quantity in hours"),
            "the equivalent value in seconds"
        ),
        "hw_carrier_count" to d(
            "expected number of heterozygous carriers in a population. Define: hw_carrier_count(q2_rec_freq, pop_size) as a block: q_val := √q2_rec_freq -> p_val := 1 - q_val -> 2·p_val·q_val·pop_size",
            listOf("q2_rec_freq — recessive phenotype frequency q² from field data", "pop_size — total population size"),
            "the expected number of carriers (real-valued count)"
        ),
        "hw_hetero" to d(
            "Hardy-Weinberg heterozygote frequency 2pq. Define: hw_hetero(p_dom) = 2·p_dom·(1 - p_dom)",
            listOf("p_dom — dominant allele frequency p"),
            "a genotype frequency in [0, 1]"
        ),
        "hw_homo_dom" to d(
            "Hardy-Weinberg dominant homozygote frequency p². Define: hw_homo_dom(p_dom) = p_dom^2",
            listOf("p_dom — dominant allele frequency p"),
            "a genotype frequency in [0, 1]"
        ),
        "hw_homo_rec" to d(
            "Hardy-Weinberg recessive homozygote frequency q². Define: hw_homo_rec(p_dom) = (1 - p_dom)^2",
            listOf("p_dom — dominant allele frequency p"),
            "a genotype frequency in [0, 1]"
        ),
        "hw_p_from_q2" to d(
            "dominant allele frequency p recovered from recessive phenotype q². Define: hw_p_from_q2(q2_rec_freq) = 1 - √q2_rec_freq",
            listOf("q2_rec_freq — recessive (aa) phenotype frequency observed in the population"),
            "an allele frequency in [0, 1]"
        ),
        "hw_q_from_p" to d(
            "recessive allele frequency q = 1 − p. Define: hw_q_from_p(p_dom) = 1 - p_dom",
            listOf("p_dom — dominant allele frequency p"),
            "an allele frequency in [0, 1]"
        ),
        "hydro_pressure" to d(
            "hydrostatic pressure at depth P = ρ·g·h. Define: hydro_pressure(rho, depth_h) = rho·g0·depth_h",
            listOf("rho — fluid density (kg/m³)", "depth_h — depth below surface h (m)"),
            "a pressure in pascals (Pa)"
        ),
        "ideal_gas_n" to d(
            "ideal gas law solved for moles n = PV/RT. Define: ideal_gas_n(press_pa, vol_m3, temp_k) = (press_pa·vol_m3) / (R_gas·temp_k)",
            listOf("press_pa — pressure (Pa)", "vol_m3 — volume (m³)", "temp_k — temperature (sec K or K)"),
            "an amount in moles (mol)"
        ),
        "ideal_gas_p" to d(
            "ideal gas law solved for pressure P = nRT/V. Define: ideal_gas_p(n_mol, temp_k, vol_m3) = (n_mol·R_gas·temp_k) / vol_m3",
            listOf("n_mol — amount of gas (mol)", "temp_k — temperature (K)", "vol_m3 — volume (m³)"),
            "a pressure in pascals (Pa)"
        ),
        "ideal_gas_t" to d(
            "ideal gas law solved for temperature T = PV/nR. Define: ideal_gas_t(press_pa, vol_m3, n_mol) = (press_pa·vol_m3) / (n_mol·R_gas)",
            listOf("press_pa — pressure (Pa)", "vol_m3 — volume (m³)", "n_mol — amount of gas (mol)"),
            "a time in seconds (s)"
        ),
        "ideal_gas_v" to d(
            "ideal gas law solved for volume V = nRT/P. Define: ideal_gas_v(n_mol, temp_k, press_pa) = (n_mol·R_gas·temp_k) / press_pa",
            listOf("n mol — amount of substance (mol)", "temp k — temperature (K)", "press pa — pressure (Pa)"),
            "a volume in m³ (or consistent with R’s unit set)"
        ),
        "impulse" to d(
            "impulse J = F·Δt (equals the momentum change). Define: impulse(force_f, dt) = force_f·dt",
            listOf("force f — force (N)", "dt — Δt (time interval)"),
            "an impulse in N·s (equivalently kg·m/s)"
        ),
        "in_to_cm" to d(
            "Unit conversion from inches (in) to centimetres (cm). Formula: 1 in = in_v·2.54 cm",
            listOf("in_v — a quantity in inches (in)"),
            "the equivalent value in centimetres (cm)"
        ),
        "in_to_ft" to d(
            "Unit conversion from inches (in) to feet (ft). Formula: 1 in = x / 12 ft",
            listOf("x — a quantity in inches (in)"),
            "the equivalent value in feet (ft)"
        ),
        "in_to_m" to d(
            "Unit conversion from inches (in) to metres (m). Formula: 1 in = x·0.0254 m",
            listOf("x — a quantity in inches (in)"),
            "the equivalent value in metres (m)"
        ),
        "incline_normal" to d(
            "normal reaction on an incline N = m·g·cosθ. Define: incline_normal(mass, theta_deg) = mass·g0·cos(deg2rad(theta_deg))",
            listOf("mass — mass (kg)", "θ deg — degrees (°)"),
            "a force in newtons (N)"
        ),
        "incline_slide_acc" to d(
            "acceleration sliding down an incline with friction. Define: incline_slide_acc(theta_deg, mu_k) = g0·(sin(deg2rad(theta_deg)) - mu_k·cos(deg2rad(theta_deg)))",
            listOf("θ deg — degrees (°)", "μ k — temperature (K)"),
            "an acceleration in m/s²"
        ),
        "ind_energy" to d(
            "energy stored in an inductor U = ½LI². Define: ind_energy(ind_l, current_i) = ½·ind_l·(current_i^2)",
            listOf("ind_l — inductance L (H, henrys)", "current_i — current I (A)"),
            "an energy in joules (J)"
        ),
        "inductive_reactance" to d(
            "inductive reactance X_L = 2πfL. Define: inductive_reactance(freq_hz, ind_l) = 2π·freq_hz·ind_l",
            listOf("freq hz — frequency (Hz)", "ind l — volume (L)"),
            "a reactance in ohms (Ω)"
        ),
        "inelastic_v" to d(
            "common velocity after a perfectly inelastic 1D collision. Define: inelastic_v(m1, u1, m2, u2) = (m1·u1 + m2·u2) / (m1 + m2)",
            listOf("m1 — first mass (kg)", "u1 — velocity of mass 1 before impact (m/s)", "m2 — second mass (kg)", "u2 — velocity of mass 2 before impact (m/s)"),
            "a common velocity in m/s"
        ),
        "is_close" to d(
            "floating-point near-equality test within a tolerance (robust equality for reals). Define: is_close(a, b, tol) = (@abs(a - b) <= tol). Default tol = 0.000001",
            listOf("a — first real value", "b — second real value", "tol — absolute tolerance (default 1e-6)"),
            "true / false — whether the values agree within tol"
        ),
        "is_even" to d(
            "parity test: true when n is even. Define: is_even(n) = (n % 2 == 0)",
            listOf("n — integer to test"),
            "true / false"
        ),
        "is_inf" to d(
            "true when the value is ±infinity (e.g. from division by zero). Define: is_inf(x) = (@abs(x) == inf)",
            listOf("x — value to test for ±infinity"),
            "true / false"
        ),
        "is_nan" to d(
            "true when the value is NaN (not a number, e.g. from 0 ÷ 0). Define: is_nan(x) = (x != x)",
            listOf("x — value to test for NaN"),
            "true / false"
        ),
        "is_odd" to d(
            "parity test: true when n is odd. Define: is_odd(n) = (n % 2 != 0)",
            listOf("n — integer to test"),
            "true / false"
        ),
        "kJ_to_J" to d(
            "Unit conversion from kilojoules (kJ) to joules (J). Formula: 1 kJ = x·1000 J",
            listOf("x — a quantity in kilojoules (kJ)"),
            "the equivalent value in joules (J)"
        ),
        "kJ_to_kcal" to d(
            "Unit conversion from kilojoules (kJ) to kilocalories (kcal). Formula: 1 kJ = x / 4.184 kcal",
            listOf("x — a quantity in kilojoules (kJ)"),
            "the equivalent value in kilocalories (kcal)"
        ),
        "kN_to_N" to d(
            "Unit conversion from kilonewtons (kN) to newtons (N). Formula: 1 kN = x·1000 N",
            listOf("x — a quantity in kilonewtons (kN)"),
            "the equivalent value in newtons (N)"
        ),
        "kPa_to_Pa" to d(
            "Unit conversion from kilopascals (kPa) to pascals (Pa). Formula: 1 kPa = x·1000 Pa",
            listOf("x — a quantity in kilopascals (kPa)"),
            "the equivalent value in pascals (Pa)"
        ),
        "kW_to_W" to d(
            "Unit conversion from kilowatts (kW) to watts (W). Formula: 1 kW = x·1000 W",
            listOf("x — a quantity in kilowatts (kW)"),
            "the equivalent value in watts (W)"
        ),
        "kW_to_hp" to d(
            "Unit conversion from kilowatts (kW) to mechanical horsepower (hp). Formula: 1 kW = x / 0.745699872 hp",
            listOf("x — a quantity in kilowatts (kW)"),
            "the equivalent value in mechanical horsepower (hp)"
        ),
        "kWh_to_J" to d(
            "Unit conversion from kilowatt-hours (kWh) to joules (J). Formula: 1 kWh = x·3600000 J",
            listOf("x — a quantity in kilowatt-hours (kWh)"),
            "the equivalent value in joules (J)"
        ),
        "k_to_c" to d(
            "Unit conversion from kelvin (K) to degrees Celsius (°C). Formula: 1 k = k - 273.15 c",
            listOf("k — a quantity in kelvin (K)"),
            "the equivalent value in degrees Celsius (°C)"
        ),
        "k_to_f" to d(
            "Unit conversion from kelvin (K) to degrees Fahrenheit (°F). Formula: 1 k = (k - 273.15)·1.8 + 32 f",
            listOf("k — a quantity in kelvin (K)"),
            "the equivalent value in degrees Fahrenheit (°F)"
        ),
        "ka_from_pka" to d(
            "acid dissociation constant from pKa: Ka = 10^−pKa. Define: ka_from_pka(pka) = 10^(-pka)",
            listOf("pka — the pKa of the acid"),
            "Ka = 10^(−pKa)"
        ),
        "kcal_to_J" to d(
            "Unit conversion from kilocalories (kcal) to joules (J). Formula: 1 kcal = x·4184 J",
            listOf("x — a quantity in kilocalories (kcal)"),
            "the equivalent value in joules (J)"
        ),
        "kcal_to_kJ" to d(
            "Unit conversion from kilocalories (kcal) to kilojoules (kJ). Formula: 1 kcal = x·4.184 kJ",
            listOf("x — a quantity in kilocalories (kcal)"),
            "the equivalent value in kilojoules (kJ)"
        ),
        "ke" to d(
            "translational kinetic energy ½mv². Define: ke(mass, vel) = ½·mass·(vel^2)",
            listOf("mass — mass of the body (kg)", "vel — speed (m/s)"),
            "kinetic energy in joules (J)"
        ),
        "kepler_period" to d(
            "Kepler third-law orbital period T = 2π√(a³/GM). Define: kepler_period(semi_major_a, mass_body) = 2π·√((semi_major_a^3) / (G_grav·mass_body))",
            listOf("semi major a — length/distance (m)", "mass body — mass (kg)"),
            "an orbital period in seconds (s)"
        ),
        "kg_to_g" to d(
            "Unit conversion from kilograms (kg) to grams (g). Formula: 1 kg = x·1000 g",
            listOf("x — a quantity in kilograms (kg)"),
            "the equivalent value in grams (g)"
        ),
        "kg_to_lb" to d(
            "Unit conversion from kilograms (kg) to pounds (lb). Formula: 1 kg = kg_v / 0.45359237 lb",
            listOf("kg_v — a quantity in kilograms (kg)"),
            "the equivalent value in pounds (lb)"
        ),
        "kg_to_mg" to d(
            "Unit conversion from kilograms (kg) to milligrams (mg). Formula: 1 kg = x·(10^6) mg",
            listOf("x — a quantity in kilograms (kg)"),
            "the equivalent value in milligrams (mg)"
        ),
        "kg_to_ton" to d(
            "Unit conversion from kilograms (kg) to metric tonnes (t). Formula: 1 kg = x·0.001 ton",
            listOf("x — a quantity in kilograms (kg)"),
            "the equivalent value in metric tonnes (t)"
        ),
        "km_to_cm" to d(
            "Unit conversion from kilometres (km) to centimetres (cm). Formula: 1 km = x·100000 cm",
            listOf("x — a quantity in kilometres (km)"),
            "the equivalent value in centimetres (cm)"
        ),
        "km_to_m" to d(
            "Unit conversion from kilometres (km) to metres (m). Formula: 1 km = x·1000 m",
            listOf("x — a quantity in kilometres (km)"),
            "the equivalent value in metres (m)"
        ),
        "km_to_mi" to d(
            "Unit conversion from kilometres (km) to statute miles (mi). Formula: 1 km = x / 1.609344 mi",
            listOf("x — a quantity in kilometres (km)"),
            "the equivalent value in statute miles (mi)"
        ),
        "km_to_mm" to d(
            "Unit conversion from kilometres (km) to millimetres (mm). Formula: 1 km = x·(10^6) mm",
            listOf("x — a quantity in kilometres (km)"),
            "the equivalent value in millimetres (mm)"
        ),
        "km_to_nm" to d(
            "Unit conversion from kilometres (km) to nanometres (nm). Formula: 1 km = x·(10^12) nm",
            listOf("x — a quantity in kilometres (km)"),
            "the equivalent value in nanometres (nm)"
        ),
        "knots_to_kph" to d(
            "Unit conversion from knots (kn) to kilometres per hour (km/h). Formula: 1 knots = x·1.852 kph",
            listOf("x — a quantity in knots (kn)"),
            "the equivalent value in kilometres per hour (km/h)"
        ),
        "knots_to_mps" to d(
            "Unit conversion from knots (kn) to metres per second (m/s). Formula: 1 knots = x·(1852 / 3600) mps",
            listOf("x — a quantity in knots (kn)"),
            "the equivalent value in metres per second (m/s)"
        ),
        "kp_from_kc" to d(
            "equilibrium constant conversion Kp = Kc·(RT)^Δn. Define: kp_from_kc(kc, temp_k, delta_n_gas) = kc·((R_atm·temp_k)^delta_n_gas)",
            listOf("kc — the Kc constant", "temp_k — temperature (K)", "delta_n_gas — Δn = gas moles products − reactants"),
            "the Kp constant (purified by R, T and Δn)"
        ),
        "kph_to_knots" to d(
            "Unit conversion from kilometres per hour (km/h) to knots (kn). Formula: 1 kph = x / 1.852 knots",
            listOf("x — a quantity in kilometres per hour (km/h)"),
            "the equivalent value in knots (kn)"
        ),
        "kph_to_mph" to d(
            "Unit conversion from kilometres per hour (km/h) to miles per hour (mph). Formula: 1 kph = x / 1.609344 mph",
            listOf("x — a quantity in kilometres per hour (km/h)"),
            "the equivalent value in miles per hour (mph)"
        ),
        "kph_to_mps" to d(
            "Unit conversion from kilometres per hour (km/h) to metres per second (m/s). Formula: 1 kph = x / 3.6 mps",
            listOf("x — a quantity in kilometres per hour (km/h)"),
            "the equivalent value in metres per second (m/s)"
        ),
        "latent_heat" to d(
            "latent heat Q = m·L (phase change, no temperature change). Define: latent_heat(mass, l_spec) = mass·l_spec",
            listOf("mass — mass undergoing the phase change (kg)", "l_spec — specific latent heat L (J/kg)"),
            "a heat quantity in joules (J)"
        ),
        "lb_to_kg" to d(
            "Unit conversion from pounds (lb) to kilograms (kg). Formula: 1 lb = lb_v·0.45359237 kg",
            listOf("lb_v — a quantity in pounds (lb)"),
            "the equivalent value in kilograms (kg)"
        ),
        "lb_to_oz" to d(
            "Unit conversion from pounds (lb) to ounces (oz). Formula: 1 lb = x·16 oz",
            listOf("x — a quantity in pounds (lb)"),
            "the equivalent value in ounces (oz)"
        ),
        "lbf_to_N" to d(
            "Unit conversion from pounds-force (lbf) to newtons (N). Formula: 1 lbf = x·4.4482216152605 N",
            listOf("x — a quantity in pounds-force (lbf)"),
            "the equivalent value in newtons (N)"
        ),
        "lcm" to d(
            "least common multiple via lcm = a·b/gcd(a, b). Define: lcm(a, b) = (a == 0 || b == 0) ? 0 : @abs(a·b) / gcd(a, b)",
            listOf("a — first integer", "b — second integer"),
            "the least common multiple (positive integer)"
        ),
        "length_contraction" to d(
            "special-relativistic length contraction L = L₀/γ. Define: length_contraction(l_proper, vel) = l_proper / lorentz_gamma(vel)",
            listOf("l_proper — proper (rest-frame) length L₀", "vel — relative speed v (m/s)"),
            "the contracted length in the input’s length unit"
        ),
        "lens_focal" to d(
            "thin-lens image distance via 1/f = 1/v − 1/u. Define: lens_focal(do_dist, di_dist) = (do_dist·di_dist) / (do_dist + di_dist)",
            listOf("do_dist — object distance (m)", "di_dist — image distance (m)"),
            "an image distance in the input length unit"
        ),
        "lens_power_dioptre" to d(
            "lens power in dioptres P = 1/f. Define: lens_power_dioptre(f_meters) = 1 / f_meters",
            listOf("f_meters — focal length in metres (m)"),
            "a power in watts (W)"
        ),
        "lgamma" to d(
            "logarithm of the absolute Gamma function. Define: lgamma(x) = @lgamma(x)",
            listOf("x — argument (not a non-positive integer)"),
            "a real number (log |Γ(x)|)"
        ),
        "lincoln_petersen_pop" to d(
            "Lincoln-Petersen mark-recapture population estimate. Define: lincoln_petersen_pop(marked_m1, total_catch_c, recaptured_m2) = (marked_m1·total_catch_c) / recaptured_m2",
            listOf("marked_m1 — individuals marked & released (M)", "total_catch_c — size of the second sample (C)", "recaptured_m2 — marked individuals recaptured (R)"),
            "an estimated population size (real count)"
        ),
        "ln" to d(
            "natural logarithm (base e). Define: ln(x) = @ln(x)",
            listOf("x — positive real argument"),
            "a real number (defined for x > 0)"
        ),
        "log10" to d(
            "common logarithm (base 10). Define: log10(x) = @ln(x) / @ln(10)",
            listOf("x — positive real argument"),
            "a real number (defined for x > 0)"
        ),
        "log2" to d(
            "binary logarithm (base 2). Define: log2(x) = @ln(x) / @ln(2)",
            listOf("x — positive real argument"),
            "a real number (defined for x > 0)"
        ),
        "lorentz_gamma" to d(
            "Lorentz factor γ = 1/√(1 − β²). Define: lorentz_gamma(vel) = 1 / √(1 - (vel / c0)^2)",
            listOf("vel — relative speed v (m/s)"),
            "Lorentz factor γ ≥ 1 (dimensionless)"
        ),
        "lorentz_mag_force" to d(
            "magnetic Lorentz force F = qvB·sinθ on a moving charge. Define: lorentz_mag_force(q, vel, b_field, theta_deg) = q·vel·b_field·sin(deg2rad(theta_deg))",
            listOf("q — particle charge (C)", "vel — particle speed (m/s)", "b_field — magnetic field strength B (T)", "theta_deg — angle between v and B (°)"),
            "a force in newtons (N)"
        ),
        "loss" to d(
            "absolute loss = cost price − selling price. Define: loss(cp, sp) = cp - sp",
            listOf("sp — selling price", "cp — cost price"),
            "the loss in the same currency unit as the inputs"
        ),
        "loss_pct" to d(
            "loss as a percentage of the cost price. Define: loss_pct(cp, sp) = ((cp - sp) / cp)·100",
            listOf("cp — cost price", "sp — selling price"),
            "a dimensionless ratio/percentage"
        ),
        "m3_to_L" to d(
            "Unit conversion from cubic metres (m³) to litres (L). Formula: 1 m3 = x·1000 L",
            listOf("x — a quantity in cubic metres (m³)"),
            "the equivalent value in litres (L)"
        ),
        "mL_to_L" to d(
            "Unit conversion from millilitres (mL) to litres (L). Formula: 1 mL = x·0.001 L",
            listOf("x — a quantity in millilitres (mL)"),
            "the equivalent value in litres (L)"
        ),
        "m_to_cm" to d(
            "Unit conversion from metres (m) to centimetres (cm). Formula: 1 m = x·100 cm",
            listOf("x — a quantity in metres (m)"),
            "the equivalent value in centimetres (cm)"
        ),
        "m_to_ft" to d(
            "Unit conversion from metres (m) to feet (ft). Formula: 1 m = m / 0.3048 ft",
            listOf("m — a quantity in metres (m)"),
            "the equivalent value in feet (ft)"
        ),
        "m_to_in" to d(
            "Unit conversion from metres (m) to inches (in). Formula: 1 m = x / 0.0254 in",
            listOf("x — a quantity in metres (m)"),
            "the equivalent value in inches (in)"
        ),
        "m_to_km" to d(
            "Unit conversion from metres (m) to kilometres (km). Formula: 1 m = m / 1000 km",
            listOf("m — a quantity in metres (m)"),
            "the equivalent value in kilometres (km)"
        ),
        "m_to_mi" to d(
            "Unit conversion from metres (m) to statute miles (mi). Formula: 1 m = m / 1609.344 mi",
            listOf("m — a quantity in metres (m)"),
            "the equivalent value in statute miles (mi)"
        ),
        "m_to_mm" to d(
            "Unit conversion from metres (m) to millimetres (mm). Formula: 1 m = x·1000 mm",
            listOf("x — a quantity in metres (m)"),
            "the equivalent value in millimetres (mm)"
        ),
        "m_to_nm" to d(
            "Unit conversion from metres (m) to nanometres (nm). Formula: 1 m = x·(10^9) nm",
            listOf("x — a quantity in metres (m)"),
            "the equivalent value in nanometres (nm)"
        ),
        "m_to_um" to d(
            "Unit conversion from metres (m) to micrometres (μm). Formula: 1 m = x·(10^6) um",
            listOf("x — a quantity in metres (m)"),
            "the equivalent value in micrometres (μm)"
        ),
        "m_to_yd" to d(
            "Unit conversion from metres (m) to yards (yd). Formula: 1 m = x / 0.9144 yd",
            listOf("x — a quantity in metres (m)"),
            "the equivalent value in yards (yd)"
        ),
        "magnification_lens" to d(
            "lens magnification m = v/u. Define: magnification_lens(v_img, u_obj) = v_img / u_obj",
            listOf("v_img — image distance", "u_obj — object distance"),
            "a magnification as a × factor (negative = inverted)"
        ),
        "malus_law" to d(
            "transmitted polarised intensity through an analyser I = I₀cos²θ. Define: malus_law(i0, theta_deg) = i0·(cos(deg2rad(theta_deg))^2)",
            listOf("i0 — incident polarised intensity I₀", "theta_deg — angle between polariser and analyser (°)"),
            "a transmitted intensity in the input’s intensity unit"
        ),
        "man_days_solve" to d(
            "man-days problem: workers × days scale inversely with work. Define: man_days_solve(m1, d1, w1, m2, w2) = (m1·d1·w2) / (w1·m2)",
            listOf("m1 — workers in the first scenario", "d1 — days in the first scenario", "w1 — work units in the first scenario", "m2 — workers in the second scenario", "w2 — work units in the second scenario"),
            "the solved quantity (days, workers or man-days requested)"
        ),
        "mass_from_moles" to d(
            "mass of substance from moles m = n·M. Define: mass_from_moles(n_mol, molar_mass) = n_mol·molar_mass",
            listOf("n mol — amount of substance (mol)", "molar mass — molar mass (g/mol)"),
            "an amount of substance in moles (mol)"
        ),
        "mean_arterial_pressure" to d(
            "mean arterial pressure MAP = DP + ⅓PP. Define: mean_arterial_pressure(systolic_p, diastolic_p) = diastolic_p + (systolic_p - diastolic_p) / 3",
            listOf("systolic_p — systolic pressure (mmHg)", "diastolic_p — diastolic pressure (mmHg)"),
            "a pressure in mmHg"
        ),
        "mean_proportional" to d(
            "mean proportional (geometric mean) of two numbers. Define: mean_proportional(a, b) = √(a·b)",
            listOf("a — first value", "b — second value"),
            "the geometric mean (non-negative for non-negative inputs)"
        ),
        "mech_energy" to d(
            "total mechanical energy KE + PE. Define: mech_energy(mass, vel, height) = ke(mass, vel) + pe(mass, height)",
            listOf("mass — mass of the body (kg)", "vel — speed (m/s)", "height — elevation above the reference level (m)"),
            "total mechanical energy in joules (J)"
        ),
        "mg_to_g" to d(
            "Unit conversion from milligrams (mg) to grams (g). Formula: 1 mg = x·0.001 g",
            listOf("x — a quantity in milligrams (mg)"),
            "the equivalent value in grams (g)"
        ),
        "mg_to_kg" to d(
            "Unit conversion from milligrams (mg) to kilograms (kg). Formula: 1 mg = x·(10^-6) kg",
            listOf("x — a quantity in milligrams (mg)"),
            "the equivalent value in kilograms (kg)"
        ),
        "mi_to_km" to d(
            "Unit conversion from statute miles (mi) to kilometres (km). Formula: 1 mi = x·1.609344 km",
            listOf("x — a quantity in statute miles (mi)"),
            "the equivalent value in kilometres (km)"
        ),
        "mi_to_m" to d(
            "Unit conversion from statute miles (mi) to metres (m). Formula: 1 mi = x·1609.344 m",
            listOf("x — a quantity in statute miles (mi)"),
            "the equivalent value in metres (m)"
        ),
        "michaelis_menten" to d(
            "Michaelis-Menten velocity v = Vmax·[S]/(Km + [S]). Define: michaelis_menten(vmax, km, substrate_s) = (vmax·substrate_s) / (km + substrate_s)",
            listOf("vmax — maximum reaction velocity Vmax", "km — Michaelis constant Km (half-saturation [S])", "substrate_s — substrate concentration [S]"),
            "a reaction velocity in the unit of Vmax"
        ),
        "min_to_hr" to d(
            "Unit conversion from minutes to hours. Formula: 1 min = x / 60 hr",
            listOf("x — a quantity in minutes"),
            "the equivalent value in hours"
        ),
        "min_to_sec" to d(
            "Unit conversion from minutes to seconds. Formula: 1 min = x·60 sec",
            listOf("x — a quantity in minutes"),
            "the equivalent value in seconds"
        ),
        "minute_ventilation" to d(
            "minute ventilation = tidal volume × breaths per minute. Define: minute_ventilation(tidal_vol_ml, resp_rate_bpm) = tidal_vol_ml·resp_rate_bpm",
            listOf("tidal_vol_ml — tidal volume (mL)", "resp_rate_bpm — breaths per minute"),
            "a ventilation rate in mL/min"
        ),
        "mirror_image_v" to d(
            "spherical-mirror image distance via mirror formula. Define: mirror_image_v(f_len, u_obj) = (f_len·u_obj) / (u_obj - f_len)",
            listOf("f_len — focal length (m)", "u_obj — object distance (m, sign convention)"),
            "an image distance in the input length unit"
        ),
        "mitotic_index" to d(
            "mitotic index: fraction of cells in mitosis. Define: mitotic_index(dividing_cells, total_cells) = (dividing_cells / total_cells)·100",
            listOf("dividing_cells — cells observed in mitosis", "total_cells — total cells counted"),
            "an index in [0, 1]"
        ),
        "mm_to_cm" to d(
            "Unit conversion from millimetres (mm) to centimetres (cm). Formula: 1 mm = x·0.1 cm",
            listOf("x — a quantity in millimetres (mm)"),
            "the equivalent value in centimetres (cm)"
        ),
        "mm_to_km" to d(
            "Unit conversion from millimetres (mm) to kilometres (km). Formula: 1 mm = x·(10^-6) km",
            listOf("x — a quantity in millimetres (mm)"),
            "the equivalent value in kilometres (km)"
        ),
        "mm_to_m" to d(
            "Unit conversion from millimetres (mm) to metres (m). Formula: 1 mm = x·0.001 m",
            listOf("x — a quantity in millimetres (mm)"),
            "the equivalent value in metres (m)"
        ),
        "mm_to_um" to d(
            "Unit conversion from millimetres (mm) to micrometres (μm). Formula: 1 mm = x·1000 um",
            listOf("x — a quantity in millimetres (mm)"),
            "the equivalent value in micrometres (μm)"
        ),
        "molality" to d(
            "molality m = moles of solute / kg of solvent. Define: molality(n_solute, kg_solvent) = n_solute / kg_solvent",
            listOf("n_solute — moles of solute (mol)", "kg_solvent — solvent mass (kg)"),
            "a molality in mol/kg of solvent"
        ),
        "molar_conductivity" to d(
            "molar conductivity Λm = κ·1000/M. Define: molar_conductivity(kappa_s_cm, molarity_m) = (kappa_s_cm·1000) / molarity_m",
            listOf("kappa_s_cm — conductivity κ (S/cm)", "molarity_m — concentration (mol/L)"),
            "a molar conductivity in S·cm²/mol"
        ),
        "molarity" to d(
            "molar concentration M = moles of solute / litres of solution. Define: molarity(n_mol, vol_liters) = n_mol / vol_liters",
            listOf("n_mol — moles of solute (mol)", "vol_liters — solution volume (L)"),
            "a molar concentration in mol/L"
        ),
        "mole_fraction" to d(
            "mole fraction χ = n_component/n_total. Define: mole_fraction(n_component, n_total) = n_component / n_total",
            listOf("n_component — moles of the component", "n_total — total moles of all components"),
            "a fraction in (0, 1)"
        ),
        "moles" to d(
            "amount of substance n = mass / molar mass. Define: moles(mass_g, molar_mass) = mass_g / molar_mass",
            listOf("mass_g — mass of substance (g)", "molar_mass — molar mass (g/mol)"),
            "an amount of substance in moles (mol)"
        ),
        "momentum" to d(
            "linear momentum p = m·v. Define: momentum(mass, vel) = mass·vel",
            listOf("mass — mass of the body (kg)", "vel — speed (m/s)"),
            "a momentum in kg·m/s"
        ),
        "mph_to_kph" to d(
            "Unit conversion from miles per hour (mph) to kilometres per hour (km/h). Formula: 1 mph = x·1.609344 kph",
            listOf("x — a quantity in miles per hour (mph)"),
            "the equivalent value in kilometres per hour (km/h)"
        ),
        "mph_to_mps" to d(
            "Unit conversion from miles per hour (mph) to metres per second (m/s). Formula: 1 mph = x·0.44704 mps",
            listOf("x — a quantity in miles per hour (mph)"),
            "the equivalent value in metres per second (m/s)"
        ),
        "mps_to_knots" to d(
            "Unit conversion from metres per second (m/s) to knots (kn). Formula: 1 mps = x·(3600 / 1852) knots",
            listOf("x — a quantity in metres per second (m/s)"),
            "the equivalent value in knots (kn)"
        ),
        "mps_to_kph" to d(
            "Unit conversion from metres per second (m/s) to kilometres per hour (km/h). Formula: 1 mps = v·3.6 kph",
            listOf("v — a quantity in metres per second (m/s)"),
            "the equivalent value in kilometres per hour (km/h)"
        ),
        "mps_to_mph" to d(
            "Unit conversion from metres per second (m/s) to miles per hour (mph). Formula: 1 mps = v / 0.44704 mph",
            listOf("v — a quantity in metres per second (m/s)"),
            "the equivalent value in miles per hour (mph)"
        ),
        "mrna_bases_for_protein" to d(
            "mRNA bases needed to encode a protein chain (3 per amino acid). Define: mrna_bases_for_protein(amino_acids) = 3·(amino_acids + 1)",
            listOf("amino_acids — amino-acid count"),
            "a base count"
        ),
        "ms_to_sec" to d(
            "Unit conversion from milliseconds (ms) to seconds. Formula: 1 ms = x·0.001 sec",
            listOf("x — a quantity in milliseconds (ms)"),
            "the equivalent value in seconds"
        ),
        "ms_to_us" to d(
            "Unit conversion from milliseconds (ms) to microseconds (μs). Formula: 1 ms = x·1000 us",
            listOf("x — a quantity in milliseconds (ms)"),
            "the equivalent value in microseconds (μs)"
        ),
        "ncr" to d(
            "Regarding Number Theory & Combinatorics. Define: ncr(n, r) = (r < 0 || r > n) ? 0 : (n!) / ((r!)·((n - r)!))",
            listOf("n — total items n", "r — chosen items r (order irrelevant)"),
            "the binomial coefficient (non-negative integer)"
        ),
        "nernst_cell" to d(
            "Nernst electrode potential under non-standard conditions. Define: nernst_cell(e0_volts, n_elec, reaction_q) = e0_volts - (R_gas·298.15 / (n_elec·F_faraday))·ln(reaction_q)",
            listOf("e0_volts — standard potential E° (V)", "n_elec — electrons transferred", "reaction_q — reaction quotient Q"),
            "an electrode potential in volts (V)"
        ),
        "net_filtration_pressure" to d(
            "net glomerular filtration pressure (Starling forces). Define: net_filtration_pressure(ghp, bcops, chp) = ghp - (bcops + chp)",
            listOf("ghp — glomerular hydrostatic pressure", "bcops — blood-colloid osmotic pressure", "chp — capsular hydrostatic pressure"),
            "a pressure in mmHg"
        ),
        "net_primary_prod" to d(
            "net primary productivity GPP − respiration. Define: net_primary_prod(gpp, respiration_r) = gpp - respiration_r",
            listOf("gpp — gross primary productivity", "respiration_r — autotroph respiration"),
            "net productivity in the inputs’ rate units"
        ),
        "nm_to_km" to d(
            "Unit conversion from nanometres (nm) to kilometres (km). Formula: 1 nm = x·(10^-12) km",
            listOf("x — a quantity in nanometres (nm)"),
            "the equivalent value in kilometres (km)"
        ),
        "nm_to_m" to d(
            "Unit conversion from nanometres (nm) to metres (m). Formula: 1 nm = x·(10^-9) m",
            listOf("x — a quantity in nanometres (nm)"),
            "the equivalent value in metres (m)"
        ),
        "nm_to_um" to d(
            "Unit conversion from nanometres (nm) to micrometres (μm). Formula: 1 nm = x·0.001 um",
            listOf("x — a quantity in nanometres (nm)"),
            "the equivalent value in micrometres (μm)"
        ),
        "noncomp_inh_v" to d(
            "velocity with a non-competitive inhibitor (Vmax effectively lowered). Define: noncomp_inh_v(vmax, km, substrate_s, inh_i, ki) = ((vmax / (1 + inh_i / ki))·substrate_s) / (km + substrate_s)",
            listOf("vmax — maximum velocity", "km — Michaelis constant", "substrate_s — substrate concentration [S]", "inh_i — inhibitor concentration [I]", "ki — inhibition constant"),
            "a reaction velocity in the unit of Vmax"
        ),
        "norm_pdf" to d(
            "normal (Gaussian) probability density at x. Define: norm_pdf(x, mu, s) = (1 / (s·√(2π)))·@exp(-½·(((x - mu) / s)^2)). Default mu = 0. Default s = 1",
            listOf("x — point on the axis", "mu — mean μ", "sigma — standard deviation σ > 0"),
            "a density value ≥ 0 (units: inverse of the x unit)"
        ),
        "normality_from_molarity" to d(
            "normality from molarity via basicity/acidity factor. Define: normality_from_molarity(molarity_m, n_factor) = molarity_m·n_factor",
            listOf("molarity_m — molarity (mol/L)", "n_factor — basicity/acidity (equivalents per formula unit)"),
            "a normality in equivalents per litre (eq/L)"
        ),
        "npr" to d(
            "Regarding Number Theory & Combinatorics. Define: npr(n, r) = (r < 0 || r > n) ? 0 : (n!) / ((n - r)!)",
            listOf("n — total items n", "r — chosen items r (order matters)"),
            "the number of ordered arrangements (non-negative integer)"
        ),
        "ns_to_sec" to d(
            "Unit conversion from nanoseconds (ns) to seconds. Formula: 1 ns = x·(10^-9) sec",
            listOf("x — a quantity in nanoseconds (ns)"),
            "the equivalent value in seconds"
        ),
        "ns_to_us" to d(
            "Unit conversion from nanoseconds (ns) to microseconds (μs). Formula: 1 ns = x·0.001 us",
            listOf("x — a quantity in nanoseconds (ns)"),
            "the equivalent value in microseconds (μs)"
        ),
        "nthroot" to d(
            "real nth root of a (degree b), handling odd roots of negatives. Define: nthroot(x, n) = root(x, n)",
            listOf("x — radicand (value under the root)", "n — root degree"),
            "the signed real root"
        ),
        "oh_from_poh" to d(
            "hydroxide concentration from pOH: [OH⁻] = 10^−pOH. Define: oh_from_poh(poh_val) = 10^(-poh_val)",
            listOf("poh_val — the pOH of the solution"),
            "a hydroxide concentration in mol/L"
        ),
        "ohm_i" to d(
            "Ohm law current I = V/R. Define: ohm_i(volt_v, res_r) = volt_v / res_r",
            listOf("volt_v — potential difference V (V)", "res_r — resistance R (Ω)"),
            "a current in amperes (A)"
        ),
        "ohm_r" to d(
            "Ohm law resistance R = V/I. Define: ohm_r(volt_v, current_i) = volt_v / current_i",
            listOf("volt_v — potential difference V (V)", "current_i — current I (A)"),
            "a resistance in ohms (Ω)"
        ),
        "ohm_v" to d(
            "Ohm law potential difference V = I·R. Define: ohm_v(current_i, res_r) = current_i·res_r",
            listOf("current_i — current I (A)", "res_r — resistance R (Ω)"),
            "a potential in volts (V)"
        ),
        "orbital_vel" to d(
            "circular orbital velocity v = √(GM/R). Define: orbital_vel(mass_body, r) = √(G_grav·mass_body / r)",
            listOf("mass_body — mass of the central body (kg)", "r — orbital radius (m)"),
            "a speed in m/s"
        ),
        "osmotic_pressure" to d(
            "osmotic pressure π = M·R·T. Define: osmotic_pressure(molarity_m, temp_k) = molarity_m·R_atm·temp_k",
            listOf("molarity m — length/distance (m)", "temp k — temperature (K)"),
            "a pressure in pascals (Pa)"
        ),
        "oz_to_g" to d(
            "Unit conversion from ounces (oz) to grams (g). Formula: 1 oz = x·28.349523125 g",
            listOf("x — a quantity in ounces (oz)"),
            "the equivalent value in grams (g)"
        ),
        "oz_to_lb" to d(
            "Unit conversion from ounces (oz) to pounds (lb). Formula: 1 oz = x / 16 lb",
            listOf("x — a quantity in ounces (oz)"),
            "the equivalent value in pounds (lb)"
        ),
        "parallel_plate_cap" to d(
            "parallel-plate capacitance C = ε₀A/d. Define: parallel_plate_cap(area, sep_d) = eps0·area / sep_d",
            listOf("area — plate area (m²)", "sep_d — plate separation d (m)"),
            "a capacitance in farads (F)"
        ),
        "particles_from_moles" to d(
            "Regarding Stoichiometry, Empirical Composition, Solutions & Concentration Units. Define: particles_from_moles(n_mol) = n_mol·N_A",
            listOf("n mol — amount of substance (mol)"),
            "an amount of substance in moles (mol)"
        ),
        "pcr_copies" to d(
            "total DNA copies after n PCR cycles (2ⁿ amplification). Define: pcr_copies(n_cycles) = 2^n_cycles",
            listOf("n_cycles — PCR cycle count n"),
            "a copy count (grows as 2^n)"
        ),
        "pcr_yield_from" to d(
            "PCR product yield from template amount and cycles. Define: pcr_yield_from(n0_templates, n_cycles) = n0_templates·(2^n_cycles)",
            listOf("n0_templates — starting template copies (or amount unit)", "n_cycles — PCR cycle count n"),
            "a product amount in the input’s amount unit"
        ),
        "pct_dec" to d(
            "percentage decrease from an old value to a new value. Define: pct_dec(val, pct) = val·(1 - pct / 100)",
            listOf("val — the original (old) value", "pct — percent decrease"),
            "a dimensionless ratio/percentage"
        ),
        "pct_error" to d(
            "percentage error between an experimental and a true value. Define: pct_error(measured, exact) = (@abs(measured - exact) / @abs(exact))·100",
            listOf("measured — measured (experimental) value", "exact — true (theoretical) value"),
            "a percentage (absolute when inputs are absolute)"
        ),
        "pct_inc" to d(
            "percentage increase from an old value to a new value. Define: pct_inc(val, pct) = val·(1 + pct / 100)",
            listOf("val — the original (old) value", "pct — percent increase"),
            "a dimensionless ratio/percentage"
        ),
        "pct_of" to d(
            "percentage of an amount. Define: pct_of(part, whole) = (part / whole)·100",
            listOf("part — the part", "whole — the base amount"),
            "a dimensionless ratio/percentage"
        ),
        "pct_val" to d(
            "what percentage a value is of a total. Define: pct_val(pct, total) = (pct / 100)·total",
            listOf("pct — the percentage rate", "total — the whole amount"),
            "a dimensionless ratio/percentage"
        ),
        "pe" to d(
            "gravitational potential energy mgh near Earth’s surface. Define: pe(mass, height) = mass·g0·height",
            listOf("mass — mass (kg)", "height — length/distance (m)"),
            "potential energy in joules (J)"
        ),
        "pendulum_period" to d(
            "small-angle period of a simple pendulum T = 2π√(L/g). Define: pendulum_period(length_m) = 2π·√(length_m / g0)",
            listOf("length m — length/distance (m)"),
            "a time in seconds (s)"
        ),
        "percent_composition" to d(
            "mass percentage of an element in a compound. Define: percent_composition(mass_elem, molar_mass) = (mass_elem / molar_mass)·100",
            listOf("mass_elem — mass of the element in the compound (g)", "molar_mass — molar mass of the compound (g/mol)"),
            "a mass percentage"
        ),
        "percent_yield" to d(
            "reaction percent yield = actual/theoretical × 100. Define: percent_yield(actual_y, theor_y) = (actual_y / theor_y)·100",
            listOf("actual_y — actual product mass (g)", "theor_y — theoretical product mass (g)"),
            "a percentage (can exceed 100 in impure cases)"
        ),
        "ph_from_oh" to d(
            "pH from hydroxide concentration (via pOH). Define: ph_from_oh(oh_conc) = 14 - (-log₁₀(oh_conc))",
            listOf("oh_conc — hydroxide concentration [OH⁻] (mol/L)"),
            "pH of the solution"
        ),
        "ph_from_poh" to d(
            "pH from pOH (pH + pOH = 14 at 25 °C). Define: ph_from_poh(poh_val) = 14 - poh_val",
            listOf("poh_val — the pOH of the solution"),
            "a pH value"
        ),
        "photoelectric_ke_max" to d(
            "maximum photoelectron kinetic energy KE = hν − φ. Define: photoelectric_ke_max(freq_hz, work_fn_j) = h_planck·freq_hz - work_fn_j",
            listOf("freq hz — frequency (Hz)", "work fn j — energy (J)"),
            "a maximum kinetic energy in joules (J)"
        ),
        "photon_energy" to d(
            "photon energy E = h·c/λ. Define: photon_energy(freq_hz) = h_planck·freq_hz",
            listOf("freq hz — frequency (Hz)"),
            "photon energy in joules (J)"
        ),
        "photon_wavelength" to d(
            "photon wavelength from energy λ = h·c/E. Define: photon_wavelength(wavelength_m) = h_planck·c0 / wavelength_m",
            listOf("wavelength m — length/distance (m)"),
            "wavelength in metres (m)"
        ),
        "pipe_fill_with_leak" to d(
            "time to fill a tank while a leak simultaneously empties it. Define: pipe_fill_with_leak(t_fill, t_empty) = (t_fill·t_empty) / (t_empty - t_fill)",
            listOf("t_fill — fill time without the leak", "t_empty — time the leak alone takes to empty a full tank"),
            "the fill time in the inputs’ time unit"
        ),
        "pka_from_ka" to d(
            "pKa from acid dissociation constant: pKa = −log₁₀Ka. Define: pka_from_ka(ka) = -log₁₀(ka)",
            listOf("ka — the acid dissociation constant"),
            "pKa = −log₁₀(Ka)"
        ),
        "poh_from_oh" to d(
            "pOH from hydroxide concentration pOH = −log₁₀[OH⁻]. Define: poh_from_oh(oh_conc) = -log₁₀(oh_conc)",
            listOf("oh_conc — hydroxide concentration [OH⁻] (mol/L)"),
            "a dimensionless logarithmic (pH-scale) value"
        ),
        "poh_from_ph" to d(
            "pOH from pH (pH + pOH = 14 at 25 °C). Define: poh_from_ph(ph_val) = 14 - ph_val",
            listOf("ph_val — the pH of the solution"),
            "a pOH value"
        ),
        "pop_doubling_time" to d(
            "population doubling time t = ln2/r. Define: pop_doubling_time(r_intrinsic) = ln(2) / r_intrinsic",
            listOf("r_intrinsic — intrinsic growth rate r (per time unit)"),
            "a doubling time in the input’s time unit"
        ),
        "pop_exp_growth" to d(
            "exponential population growth N = N₀e^(rt). Define: pop_exp_growth(n0, r_intrinsic, time_t) = n0·exp(r_intrinsic·time_t)",
            listOf("n0 — initial population size", "r_intrinsic — intrinsic growth rate r", "time_t — elapsed time (same unit as r)"),
            "a population size (real count)"
        ),
        "pop_logistic_dNdt" to d(
            "instantaneous logistic growth rate dN/dt = rN(1 − N/K). Define: pop_logistic_dNdt(r_intrinsic, n_cur, k_carrying) = r_intrinsic·n_cur·((k_carrying - n_cur) / k_carrying)",
            listOf("r_intrinsic — intrinsic growth rate r", "n_cur — current population size N", "k_carrying — carrying capacity K"),
            "a growth rate in individuals per time unit"
        ),
        "pop_logistic_nt" to d(
            "logistic population size at time t. Define: pop_logistic_nt(n0, k_carrying, r_intrinsic, time_t) = k_carrying / (1 + ((k_carrying - n0) / n0)·exp(-r_intrinsic·time_t))",
            listOf("n0 — initial population size", "k_carrying — carrying capacity K", "r_intrinsic — intrinsic growth rate r", "time_t — elapsed time (same unit as r)"),
            "a population size (real count)"
        ),
        "popcount" to d(
            "number of set bits (1s) in the binary form of a non-negative integer. Define: popcount(n) as a block: if (n < 0 || n != @floor(n)) { error(\"popcount() requires a non-negative integer\") } -> c := 0 -> x := n -> (x > 0) ?? c += (x % 2) -> x = @floor(x / 2) -> c",
            listOf("n — non-negative integer (trailing `none` placeholder is optional)"),
            "the number of set bits (non-negative integer)"
        ),
        "ppm_conc" to d(
            "concentration in parts per million. Define: ppm_conc(mass_solute_g, mass_soln_g) = (mass_solute_g / mass_soln_g)·(10^6)",
            listOf("mass_solute_g — solute mass (g)", "mass_soln_g — total solution mass (g)"),
            "a molar concentration in mol/L"
        ),
        "profit" to d(
            "absolute profit = selling price − cost price. Define: profit(cp, sp) = sp - cp",
            listOf("sp — selling price", "cp — cost price"),
            "the profit in the same currency unit as the inputs"
        ),
        "profit_pct" to d(
            "profit as a percentage of the cost price. Define: profit_pct(cp, sp) = ((sp - cp) / cp)·100",
            listOf("cp — cost price", "sp — selling price"),
            "a dimensionless ratio/percentage"
        ),
        "proj_max_h" to d(
            "maximum height of a projectile. Define: proj_max_h(vel, angle_deg) = ((vel·sin(deg2rad(angle_deg)))^2) / (2·g0)",
            listOf("vel — launch speed (m/s)", "angle_deg — launch angle above the horizontal (°)"),
            "a height in metres (m)"
        ),
        "proj_range" to d(
            "horizontal range of a projectile launched at an angle over level ground. Define: proj_range(vel, angle_deg) = (vel^2)·sin(2·deg2rad(angle_deg)) / g0",
            listOf("vel — launch speed (m/s)", "angle_deg — launch angle above the horizontal (°)"),
            "a horizontal range in metres (m)"
        ),
        "proj_time" to d(
            "total time of flight of a projectile. Define: proj_time(vel, angle_deg) = 2·vel·sin(deg2rad(angle_deg)) / g0",
            listOf("vel — launch speed (m/s)", "angle_deg — launch angle above the horizontal (°)"),
            "a flight time in seconds (s)"
        ),
        "psi_to_Pa" to d(
            "Unit conversion from pounds per square inch (psi) to pascals (Pa). Formula: 1 psi = x·6894.757293168 Pa",
            listOf("x — a quantity in pounds per square inch (psi)"),
            "the equivalent value in pascals (Pa)"
        ),
        "psi_to_atm" to d(
            "Unit conversion from pounds per square inch (psi) to atmospheres (atm). Formula: 1 psi = x·(6894.757293168 / 101325) atm",
            listOf("x — a quantity in pounds per square inch (psi)"),
            "the equivalent value in atmospheres (atm)"
        ),
        "pulse_pressure" to d(
            "pulse pressure = systolic − diastolic pressure. Define: pulse_pressure(systolic_p, diastolic_p) = systolic_p - diastolic_p",
            listOf("systolic_p — systolic pressure (mmHg)", "diastolic_p — diastolic pressure (mmHg)"),
            "a pressure in mmHg"
        ),
        "q10_from_rates" to d(
            "Q10 coefficient from rates at two temperatures. Define: q10_from_rates(r1, r2, t1_c, t2_c) = (r2 / r1)^(10 / (t2_c - t1_c))",
            listOf("r1 — rate at t1_c", "r2 — rate at t2_c", "t1_c — first temperature (°C)", "t2_c — second temperature (°C)"),
            "the dimensionless Q10 coefficient"
        ),
        "q10_rate" to d(
            "reaction rate scaled by the Q10 temperature coefficient. Define: q10_rate(r1, t1_c, t2_c, q10_coeff) = r1·(q10_coeff^((t2_c - t1_c) / 10)). Default q10 coeff = 2",
            listOf("r1 — rate measured at t1_c", "t1_c — reference temperature (°C)", "t2_c — second temperature (°C)", "q10_coeff — Q10 coefficient (default 2)"),
            "a reaction rate in the input rates’ unit"
        ),
        "quad_critical_x" to d(
            "critical x (vertex abscissa) of a quadratic. Define: quad_critical_x(a, b, c_term) = -b / (2a) + 0·c_term. Default c term = 0",
            listOf("a — x² coefficient", "b — x coefficient", "c — constant term"),
            "the vertex x-value"
        ),
        "quad_extremum_y" to d(
            "extremum (vertex) y-value of a quadratic. Define: quad_extremum_y(a, b, c_term) = c_term - (b^2) / (4a)",
            listOf("a — x² coefficient", "b — x coefficient", "c — constant term"),
            "the extremum (vertex) y-value"
        ),
        "rad_to_arcsec" to d(
            "Unit conversion from radians (rad) to arcseconds (″). Formula: 1 rad = r·(180·3600) / π arcsec",
            listOf("r — a quantity in radians (rad)"),
            "the equivalent value in arcseconds (″)"
        ),
        "rad_to_deg" to d(
            "Unit conversion from radians (rad) to degrees (°). Formula: 1 rad = r·180 / π deg",
            listOf("r — a quantity in radians (rad)"),
            "the equivalent value in degrees (°)"
        ),
        "radiocarbon_age" to d(
            "radiocarbon dating age of a sample. Define: radiocarbon_age(fraction_rem) = -5730·(ln(fraction_rem) / ln(2))",
            listOf("fraction_rem — remaining C-14 fraction of the live ratio (0..1)"),
            "age of the sample in years"
        ),
        "raoult_p_soln" to d(
            "vapour pressure of a solution via Raoult law. Define: raoult_p_soln(x_solvent, p0_solvent) = x_solvent·p0_solvent",
            listOf("x_solvent — solvent mole fraction", "p0_solvent — pure solvent vapour pressure (Vvap)"),
            "a vapour pressure in the units of the pure pressures"
        ),
        "rate_const_1st" to d(
            "first-order rate constant from half-life k = ln2/t½. Define: rate_const_1st(t_sec, c0, ct) = (1 / t_sec)·ln(c0 / ct)",
            listOf("t_sec — elapsed time (s)", "c0 — initial concentration ([A]₀)", "ct — concentration at time t ([A]t)"),
            "a rate constant in inverse time units"
        ),
        "rc_tau" to d(
            "RC circuit time constant τ = R·C. Define: rc_tau(res_r, cap_c) = res_r·cap_c",
            listOf("res_r — resistance R (Ω)", "cap_c — capacitance C (F)"),
            "a time constant in seconds (s)"
        ),
        "recoil_vel" to d(
            "recoil speed of a gun after firing (momentum conservation). Define: recoil_vel(m_gun, m_bullet, v_bullet) = -(m_bullet·v_bullet) / m_gun",
            listOf("m_gun — mass of the gun (kg)", "m_bullet — mass of the bullet (kg)", "v_bullet — muzzle velocity (m/s)"),
            "a speed in m/s"
        ),
        "recomb_freq" to d(
            "genetic recombination frequency (map distance in cM). Define: recomb_freq(recombinants, total_offspring) = (recombinants / total_offspring)·100",
            listOf("recombinants — recombinant offspring count", "total_offspring — total offspring count"),
            "a map distance in centimorgans (cM, 0–50 typically)"
        ),
        "rel_energy" to d(
            "total relativistic energy E = γm₀c². Define: rel_energy(mass, vel) = lorentz_gamma(vel)·mass·(c0^2)",
            listOf("mass — rest mass m₀ (kg)", "vel — relative speed (m/s)"),
            "a total energy in joules (J)"
        ),
        "rel_speed_opp" to d(
            "relative speed when moving in opposite directions (speeds add). Define: rel_speed_opp(s1, s2) = s1 + s2",
            listOf("s1 — speed of object 1 (m/s)", "s2 — speed of object 2 (m/s)"),
            "a relative speed in the inputs’ speed unit"
        ),
        "rel_speed_same" to d(
            "relative speed when moving in the same direction (speeds differ). Define: rel_speed_same(s1, s2) = @abs(s1 - s2)",
            listOf("s1 — faster object speed (m/s)", "s2 — slower object speed (m/s)"),
            "a relative speed in the inputs’ speed unit"
        ),
        "renal_clearance" to d(
            "renal clearance Cx = (Ux·V)/Px. Define: renal_clearance(urine_conc_u, urine_flow_v, plasma_conc_p) = (urine_conc_u·urine_flow_v) / plasma_conc_p",
            listOf("urine_conc_u — urine concentration Ux", "urine_flow_v — urine flow rate V (mL/min)", "plasma_conc_p — plasma concentration Px"),
            "a clearance in mL/min"
        ),
        "res_digest_circular" to d(
            "respiration rate of a circular filter-paper disc setup. Define: res_digest_circular(cut_sites) = cut_sites",
            listOf("cut_sites — number of cut sites/ticks recorded"),
            "a respiration rate (e.g. mm CO₂/min)"
        ),
        "res_digest_linear" to d(
            "respiration rate of a linear filter-paper strip setup. Define: res_digest_linear(cut_sites) = cut_sites + 1",
            listOf("cut_sites — number of cut sites/ticks recorded"),
            "a respiration rate (e.g. mm CO₂/min)"
        ),
        "res_series" to d(
            "equivalent resistance of a series pair R = R₁ + R₂. Define: res_series(r1, r2) = r1 + r2",
            listOf("r1 — first resistance (Ω)", "r2 — second resistance (Ω)"),
            "a resistance in ohms (Ω)"
        ),
        "respiratory_quotient" to d(
            "respiratory quotient RQ = CO₂ produced/O₂ consumed. Define: respiratory_quotient(co2_produced, o2_consumed) = co2_produced / o2_consumed",
            listOf("co2_produced — CO₂ produced (mol or volume units)", "o2_consumed — O₂ consumed (same units)"),
            "a dimensionless RQ"
        ),
        "rlc_impedance" to d(
            "series RLC impedance magnitude Z = √(R² + (X_L − X_C)²). Define: rlc_impedance(res_r, xl, xc) = √((res_r^2) + ((xl - xc)^2))",
            listOf("res_r — resistance R (Ω)", "xl — inductive reactance X_L (Ω)", "xc — capacitive reactance X_C (Ω)"),
            "an impedance magnitude in ohms (Ω)"
        ),
        "rlc_resonant_freq" to d(
            "series RLC resonance f₀ = 1/(2π√(LC)). Define: rlc_resonant_freq(ind_l, cap_c) = 1 / (2π·√(ind_l·cap_c))",
            listOf("ind l — volume (L)", "cap c — °C"),
            "a frequency in hertz (Hz)"
        ),
        "rms_mol_speed" to d(
            "root-mean-square molecular speed v = √(3RT/M). Define: rms_mol_speed(temp_k, molar_mass_kg) = √(3·R_gas·temp_k / molar_mass_kg)",
            listOf("temp k — temperature (K)", "molar mass kg — mass (kg)"),
            "a speed in m/s"
        ),
        "rot_ke" to d(
            "rotational kinetic energy ½Iω². Define: rot_ke(inertia_i, omega) = ½·inertia_i·(omega^2)",
            listOf("inertia_i — moment of inertia I (kg·m²)", "omega — angular velocity ω (rad/s)"),
            "a rotational kinetic energy in joules (J)"
        ),
        "round" to d(
            "rounding to the nearest value with given decimal digits. Define: round(x, digits) as a block: if (digits != digits || digits != @floor(digits)) { error(\"round() digits must be an integer\") } -> if (digits > 300 || x == 0 || x != x) { return x } -> if (digits < -308) { return 0 } -> if (digits >= 0) s := 10^digits -> ((x >= 0) ? @floor(x·s + 0.5) : @ceil(x·s - 0.5)) / s else s := 10^(-digits) -> ((x >= 0) ? @floor(x / s + 0.5) : @ceil(x / s - 0.5))·s. Default digits = 0",
            listOf("x — value to round", "digits — decimal places to keep"),
            "a real number rounded to the requested digits"
        ),
        "rydberg_wavelength" to d(
            "hydrogen spectral-line wavelength via the Rydberg formula. Define: rydberg_wavelength(n1, n2) = 1 / (rydberg_R·((1 / (n1^2)) - (1 / (n2^2))))",
            listOf("n1 — lower shell n (int, 1, 2, …)", "n2 — upper shell n (int, n2 > n1)"),
            "a wavelength in metres (m)"
        ),
        "sa_to_vol_sphere" to d(
            "surface-area-to-volume ratio of a sphere (diffusion efficiency). Define: sa_to_vol_sphere(r) = 3 / r",
            listOf("r — sphere radius (m)"),
            "an area-to-volume ratio in inverse length units"
        ),
        "same_dim" to d(
            "true when two unit-tagged values carry the same 7D SI dimension. Define: same_dim(a, b) = (dim_vec(a) == dim_vec(b))",
            listOf("a — first unit-tagged value", "b — second unit-tagged value"),
            "true / false — same 7D SI dimension"
        ),
        "schwarzschild_r" to d(
            "Schwarzschild (event-horizon) radius of a mass. Define: schwarzschild_r(mass_body) = 2·G_grav·mass_body / (c0^2)",
            listOf("mass body — mass (kg)"),
            "event-horizon radius in metres (m)"
        ),
        "sec" to d(
            "secant = 1/cos(x). Define: sec(x) = 1 / @cos(x)",
            listOf("x — argument in radians (the calculator’s deg/rad mode also honours tagged values)"),
            "a real number (|value| ≥ 1 outside rounding)"
        ),
        "sec_to_day" to d(
            "Unit conversion from seconds to days. Formula: 1 sec = x / 86400 day",
            listOf("x — a quantity in seconds"),
            "the equivalent value in days"
        ),
        "sec_to_hr" to d(
            "Unit conversion from seconds to hours. Formula: 1 sec = x / 3600 hr",
            listOf("x — a quantity in seconds"),
            "the equivalent value in hours"
        ),
        "sec_to_min" to d(
            "Unit conversion from seconds to minutes. Formula: 1 sec = x / 60 min",
            listOf("x — a quantity in seconds"),
            "the equivalent value in minutes"
        ),
        "sec_to_ms" to d(
            "Unit conversion from seconds to milliseconds (ms). Formula: 1 sec = x·1000 ms",
            listOf("x — a quantity in seconds"),
            "the equivalent value in milliseconds (ms)"
        ),
        "sec_to_ns" to d(
            "Unit conversion from seconds to nanoseconds (ns). Formula: 1 sec = x·(10^9) ns",
            listOf("x — a quantity in seconds"),
            "the equivalent value in nanoseconds (ns)"
        ),
        "second_order_half_life" to d(
            "half-life of a second-order reaction t½ = 1/(k·[A]₀). Define: second_order_half_life(c0, rate_k) = 1 / (rate_k·c0)",
            listOf("c0 — initial concentration [A]₀", "rate_k — second-order rate constant k"),
            "a half-life in the units of [A]₀/k"
        ),
        "sensible_heat" to d(
            "sensible heat Q = m·c·ΔT. Define: sensible_heat(mass, c_spec, dt) = mass·c_spec·dt",
            listOf("mass — mass heated (kg)", "c_spec — specific heat capacity c (J/kg·K)", "dt — temperature change ΔT (K)"),
            "a heat quantity in joules (J)"
        ),
        "shm_acc" to d(
            "instantaneous SHM acceleration a = −ω²x. Define: shm_acc(omega, disp_x) = -(omega^2)·disp_x",
            listOf("omega — angular frequency ω (rad/s)", "disp_x — displacement from equilibrium x (m)"),
            "an acceleration in m/s²"
        ),
        "shm_omega" to d(
            "angular frequency of a mass-spring oscillator ω = √(k/m). Define: shm_omega(k_spring, mass) = √(k_spring / mass)",
            listOf("k_spring — spring constant k (N/m)", "mass — attached mass (kg)"),
            "an angular frequency in rad/s"
        ),
        "shm_period_spring" to d(
            "period of a mass-spring oscillator T = 2π√(m/k). Define: shm_period_spring(mass, k_spring) = 2π·√(mass / k_spring)",
            listOf("mass — attached mass (kg)", "k_spring — spring constant k (N/m)"),
            "a time in seconds (s)"
        ),
        "shm_vel" to d(
            "instantaneous SHM velocity v = ω√(A² − x²). Define: shm_vel(omega, amp_a, disp_x) = omega·√((amp_a^2) - (disp_x^2))",
            listOf("omega — angular frequency ω (rad/s)", "amp_a — amplitude A (m)", "disp_x — displacement from equilibrium x (m)"),
            "a speed in m/s"
        ),
        "si_amount" to d(
            "total amount (principal + interest) under simple interest. Define: si_amount(p, r_pct, t_yr) = p + simple_interest(p, r_pct, t_yr)",
            listOf("p — principal", "r_pct — annual rate (%)", "t_yr — time (years)"),
            "the total amount (principal + interest) in the same currency"
        ),
        "sign" to d(
            "sign of x: −1 negative, 0 zero, 1 positive. Define: sign(x) = (x != x) ? x : ((x > 0) ? 1 : (x < 0) ? -1 : 0)",
            listOf("x — real value whose signum is wanted"),
            "−1, 0 or 1"
        ),
        "simple_interest" to d(
            "simple interest I = P·R·T/100. Define: simple_interest(p, r_pct, t_yr) = (p·r_pct·t_yr) / 100",
            listOf("p — principal", "r_pct — annual rate (%)", "t_yr — time (years)"),
            "the interest in the same currency unit as the principal"
        ),
        "sin" to d(
            "sine of x (radians; honours the calculator’s deg/rad mode). Define: sin(x) = @sin(x)",
            listOf("x — argument in radians (the calculator’s deg/rad mode also honours tagged values)"),
            "a value in [−1, 1] (sine rises and falls within this band)"
        ),
        "sinh" to d(
            "hyperbolic sine (e^x − e^−x)/2. Define: sinh(x) = ½·(@exp(x) - @exp(-x))",
            listOf("x — real argument"),
            "a real number (unbounded)"
        ),
        "snell_angle2" to d(
            "refraction angle from Snell law n₁sinθ₁ = n₂sinθ₂. Define: snell_angle2(n1, theta1_deg, n2) = rad2deg(asin(n1·sin(deg2rad(theta1_deg)) / n2))",
            listOf("n1 — refractive index of medium 1", "theta1_deg — incidence angle θ₁ (°)", "n2 — refractive index of medium 2"),
            "an angle in degrees (°)"
        ),
        "solenoid_b" to d(
            "magnetic field inside a long solenoid B = μ₀·n·I. Define: solenoid_b(n_turns_per_m, current_i) = mu0·n_turns_per_m·current_i",
            listOf("n_turns_per_m — turns per metre of the solenoid", "current_i — coil current I (A)"),
            "a magnetic field in tesla (T)"
        ),
        "solubility_ksp_ab" to d(
            "molar solubility from Ksp of a salt AB. Define: solubility_ksp_ab(ksp) = √ksp",
            listOf("ksp — solubility product of salt AB (A⁺B⁻)"),
            "molar solubility s in mol/L"
        ),
        "solubility_ksp_ab2" to d(
            "molar solubility from Ksp of a salt AB₂. Define: solubility_ksp_ab2(ksp) = (ksp / 4)^(1 / 3)",
            listOf("ksp — solubility product of salt AB₂ (A⁺2B⁻)"),
            "molar solubility s in mol/L"
        ),
        "solute_potential" to d(
            "solute (osmotic) potential Ψs = −iCRT. Define: solute_potential(i_ion, molarity_c, temp_k) = -i_ion·molarity_c·0.0831·temp_k",
            listOf("i_ion — ionisation constant i (van’t Hoff factor)", "molarity_c — molar concentration (mol/L)", "temp_k — absolute temperature (K)"),
            "a potential in bars or MPa (per input unit set)"
        ),
        "sound_db" to d(
            "sound intensity level in decibels from intensity. Define: sound_db(intensity_i) = 10·log₁₀(intensity_i / (10^-12))",
            listOf("intensity_i — sound intensity (W/m²)"),
            "intensity level in decibels (dB)"
        ),
        "sp_after_discount" to d(
            "selling price after applying a discount percentage. Define: sp_after_discount(marked_p, d_pct) = marked_p·(1 - d_pct / 100)",
            listOf("marked_p — marked (list) price", "d_pct — discount percentage"),
            "the discounted price in the same currency unit"
        ),
        "sp_from_loss_pct" to d(
            "selling price from cost price at a loss percentage. Define: sp_from_loss_pct(cp, l_pct) = cp·(1 - l_pct / 100)",
            listOf("cp — cost price", "l_pct — loss percentage"),
            "a dimensionless ratio/percentage"
        ),
        "sp_from_profit_pct" to d(
            "selling price from cost price at a profit percentage. Define: sp_from_profit_pct(cp, p_pct) = cp·(1 + p_pct / 100)",
            listOf("cp — cost price", "p_pct — profit percentage"),
            "a dimensionless ratio/percentage"
        ),
        "sphere_area" to d(
            "Define: sphere_area(r) = 4π·(r^2)",
            listOf("r — sphere radius (m)"),
            "a surface area in the radius unit squared"
        ),
        "sphere_vol" to d(
            "Define: sphere_vol(r) = (4 / 3)·π·(r^3)",
            listOf("r — sphere radius (m)"),
            "a volume in the radius unit cubed"
        ),
        "sqrt" to d(
            "principal square root. Define: sqrt(x) = @sqrt(x)",
            listOf("x — non-negative real argument"),
            "a non-negative real number (principal root)"
        ),
        "stefan_boltzmann_p" to d(
            "Stefan-Boltzmann radiated power P = ε·σ·A·T⁴. Define: stefan_boltzmann_p(area, emissivity, temp_k) = area·emissivity·sigma_sb·(temp_k^4)",
            listOf("area — emitting surface area (m²)", "emissivity — emissivity ε (0–1)", "temp_k — absolute temperature (K)"),
            "a radiated power in watts (W)"
        ),
        "stokes_drag" to d(
            "Stokes viscous drag on a sphere F = 6πηrv. Define: stokes_drag(eta, r, vel) = 6π·eta·r·vel",
            listOf("eta — dynamic viscosity (Pa·s)", "r — sphere radius (m)", "vel — speed (m/s)"),
            "a drag force in newtons (N)"
        ),
        "stomatal_index" to d(
            "stomatal index: stomata fraction of epidermal cells. Define: stomatal_index(stomata_s, epidermal_e) = (stomata_s / (stomata_s + epidermal_e))·100",
            listOf("stomata_s — number of stomata counted", "epidermal_e — number of other epidermal cells counted"),
            "an index in [0, 1]"
        ),
        "strain_val" to d(
            "strain ΔL/L. Define: strain_val(dl, l0) = dl / l0",
            listOf("dl — extension ΔL (m)", "l0 — original length L₀ (m)"),
            "a dimensionless strain (ΔL/L)"
        ),
        "stream_speed" to d(
            "stream (current) speed from upstream and downstream speeds. Define: stream_speed(d_spd, u_spd) = (d_spd - u_spd) / 2",
            listOf("d_spd — downstream speed (m/s)", "u_spd — upstream speed (m/s)"),
            "a speed in the inputs’ speed unit"
        ),
        "stress_val" to d(
            "mechanical stress σ = F/A. Define: stress_val(force_f, area) = force_f / area",
            listOf("force f — force (N)", "area — area (m²)"),
            "a stress in pascals (Pa)"
        ),
        "stroke_volume" to d(
            "stroke volume from cardiac output and heart rate. Define: stroke_volume(edv_ml, esv_ml) = edv_ml - esv_ml",
            listOf("edv_ml — end-diastolic volume (mL)", "esv_ml — end-systolic volume (mL)"),
            "a stroke volume in mL"
        ),
        "successive_discount" to d(
            "equivalent single discount of two successive discounts. Define: successive_discount(d1, d2) = d1 + d2 - (d1·d2) / 100",
            listOf("d1 — first discount (%)", "d2 — second discount (%)"),
            "a count (integer-valued real)"
        ),
        "successive_pct" to d(
            "combined percentage change of two successive percentage changes. Define: successive_pct(p1, p2) = p1 + p2 + (p1·p2) / 100",
            listOf("p1 — first percentage change (signed)", "p2 — second percentage change (signed)"),
            "a dimensionless ratio/percentage"
        ),
        "sum_cube_n" to d(
            "sum of cubes of the first n naturals [n(n+1)/2]². Define: sum_cube_n(n) = (n·(n + 1) / 2)^2",
            listOf("n — summation upper bound (positive integer)"),
            "the sum of cubes"
        ),
        "sum_n" to d(
            "sum of the first n natural numbers n(n+1)/2. Define: sum_n(n) = n·(n + 1) / 2",
            listOf("n — summation upper bound (positive integer)"),
            "the sum (integer-valued real)"
        ),
        "sum_sq_n" to d(
            "sum of squares of the first n naturals n(n+1)(2n+1)/6. Define: sum_sq_n(n) = n·(n + 1)·(2n + 1) / 6",
            listOf("n — summation upper bound (positive integer)"),
            "the sum of squares"
        ),
        "suvat_nth_sec" to d(
            "displacement in the nth second of uniform acceleration. Define: suvat_nth_sec(u, a, n) = u + ½·a·(2n - 1)",
            listOf("u — initial velocity (m/s)", "a — acceleration (m/s²)", "n — the second index (1st, 2nd, …)"),
            "a displacement size in metres (m)"
        ),
        "suvat_s_avg" to d(
            "SUVAT displacement via average velocity s = ½(u+v)·t. Define: suvat_s_avg(u, v, t) = ½·(u + v)·t",
            listOf("u — initial velocity (m/s)", "v — final velocity (m/s)", "t — elapsed time (s)"),
            "a displacement in metres (m)"
        ),
        "suvat_v" to d(
            "SUVAT final velocity v = u + a·t. Define: suvat_v(u, a, t) = u + a·t",
            listOf("u — initial velocity (m/s)", "a — uniform acceleration (m/s²)", "t — elapsed time (s)"),
            "a velocity in m/s"
        ),
        "suvat_v2" to d(
            "SUVAT final velocity without time: v² = u² + 2a·s. Define: suvat_v2(u, a, s) = √((u^2) + 2a·s)",
            listOf("u — initial velocity (m/s)", "a — acceleration (m/s²)", "s — displacement (m)"),
            "a velocity in m/s"
        ),
        "tan" to d(
            "tangent of x; asymptotes reported as errors, not huge values. Define: tan(x) = @tan(x)",
            listOf("x — argument in radians (the calculator’s deg/rad mode also honours tagged values)"),
            "a real number (unbounded; tangent asymptotes report errors)"
        ),
        "tanh" to d(
            "hyperbolic tangent. Define: tanh(x) = (@exp(x) - @exp(-x)) / (@exp(x) + @exp(-x))",
            listOf("x — real argument"),
            "a value in (−1, 1)"
        ),
        "taylor_cos" to d(
            "Maclaurin partial sum of cos(x). Define: taylor_cos(x, n_terms) = sigma(k_idx, ((-1)^k_idx)·(x^(2k_idx)) / ((2k_idx)!), 0, n_terms - 1). Default n terms = 10",
            listOf("x — point of evaluation (radians)", "n_terms — number of series terms (default 10)"),
            "the partial-sum approximation of cos(x) at that point"
        ),
        "taylor_exp" to d(
            "Maclaurin partial sum of e^x. Define: taylor_exp(x, n_terms) = sigma(k_idx, (x^k_idx) / (k_idx!), 0, n_terms - 1). Default n terms = 12",
            listOf("x — point of evaluation", "n_terms — number of series terms (default 10)"),
            "the partial-sum approximation of e^x at that point"
        ),
        "taylor_sin" to d(
            "Maclaurin partial sum of sin(x). Define: taylor_sin(x, n_terms) = sigma(k_idx, ((-1)^k_idx)·(x^(2k_idx + 1)) / ((2k_idx + 1)!), 0, n_terms - 1). Default n terms = 10",
            listOf("x — point of evaluation (radians)", "n_terms — number of series terms (default 10)"),
            "the partial-sum approximation of sin(x) at that point"
        ),
        "terminal_vel" to d(
            "terminal velocity of a sphere falling under Stokes drag. Define: terminal_vel(r, rho_sphere, rho_fluid, eta) = (2·(r^2)·(rho_sphere - rho_fluid)·g0) / (9·eta)",
            listOf("r — sphere radius (m)", "rho_sphere — sphere density (kg/m³)", "rho_fluid — fluid density (kg/m³)", "eta — dynamic viscosity (Pa·s)"),
            "a speed in m/s"
        ),
        "thermal_cond_rate" to d(
            "conduction heat-flow rate via Fourier law Q/t = k·A·ΔT/L. Define: thermal_cond_rate(k_cond, area, dt, thickness) = (k_cond·area·dt) / thickness",
            listOf("k_cond — thermal conductivity (W/m·K)", "area — cross-section (m²)", "dt — temperature difference ΔT (K)", "thickness — slab thickness (m)"),
            "a heat-flow rate in joules per second (W)"
        ),
        "thermal_expansion_l" to d(
            "linear thermal expansion ΔL = α·L₀·ΔT. Define: thermal_expansion_l(l0, alpha, dt) = l0·(1 + alpha·dt)",
            listOf("l0 — original length L₀ (m)", "alpha — coefficient of linear expansion α (1/K)", "dt — temperature change ΔT (K)"),
            "the expansion ΔL in the same length unit as L₀"
        ),
        "third_proportional" to d(
            "third proportional c of a:b::b:c, c = b²/a. Define: third_proportional(a, b) = (b^2) / a",
            listOf("a — first term", "b — second term"),
            "the third proportional value"
        ),
        "time_dilation" to d(
            "special-relativistic time dilation Δt′ = Δt·γ. Define: time_dilation(t_proper, vel) = t_proper·lorentz_gamma(vel)",
            listOf("t_proper — proper time interval Δt", "vel — relative speed v (m/s)"),
            "the dilated time in the input’s time unit"
        ),
        "ton_to_kg" to d(
            "Unit conversion from metric tonnes (t) to kilograms (kg). Formula: 1 ton = x·1000 kg",
            listOf("x — a quantity in metric tonnes (t)"),
            "the equivalent value in kilograms (kg)"
        ),
        "torque_val" to d(
            "torque τ = r·F·sinθ. Define: torque_val(r, force_f, theta_deg) = r·force_f·sin(deg2rad(theta_deg))",
            listOf("r — lever-arm distance (m)", "force_f — applied force F (N)", "theta_deg — angle between r and F (°)"),
            "a torque in N·m"
        ),
        "torr_to_atm" to d(
            "Unit conversion from torr (mmHg) to atmospheres (atm). Formula: 1 torr = x / 760 atm",
            listOf("x — a quantity in torr (mmHg)"),
            "the equivalent value in atmospheres (atm)"
        ),
        "total_lung_capacity" to d(
            "total lung capacity = VC + RV. Define: total_lung_capacity(vc_ml, rv_ml) = vc_ml + rv_ml",
            listOf("vc_ml — vital capacity (mL)", "rv_ml — residual volume (mL)"),
            "a capacity in mL"
        ),
        "total_magnification" to d(
            "total compound-microscope magnification (ocular × objective). Define: total_magnification(ocular_mag, objective_mag) = ocular_mag·objective_mag",
            listOf("ocular_mag — eyepiece magnification", "objective_mag — objective magnification"),
            "a magnification as a × factor"
        ),
        "transformer_v2" to d(
            "secondary voltage of an ideal transformer via turns ratio. Define: transformer_v2(v1, n1, n2) = v1·(n2 / n1)",
            listOf("v1 — primary voltage (V)", "n1 — primary turns", "n2 — secondary turns"),
            "a voltage in volts (V)"
        ),
        "trophic_energy_at" to d(
            "energy reaching a trophic level via the 10% rule. Define: trophic_energy_at(base_energy, trophic_steps) = base_energy·(0.1^trophic_steps)",
            listOf("base_energy — energy at the producer level (J)", "trophic_steps — trophic transfers above the producer level"),
            "available energy in the input energy unit"
        ),
        "trunc" to d(
            "truncation towards zero (fractional part removed). Define: trunc(x) = (x >= 0) ? @floor(x) : @ceil(x)",
            listOf("x — value to truncate towards zero"),
            "a real number with the fractional part removed"
        ),
        "um_to_m" to d(
            "Unit conversion from micrometres (μm) to metres (m). Formula: 1 um = x·(10^-6) m",
            listOf("x — a quantity in micrometres (μm)"),
            "the equivalent value in metres (m)"
        ),
        "um_to_mm" to d(
            "Unit conversion from micrometres (μm) to millimetres (mm). Formula: 1 um = x·0.001 mm",
            listOf("x — a quantity in micrometres (μm)"),
            "the equivalent value in millimetres (mm)"
        ),
        "um_to_nm" to d(
            "Unit conversion from micrometres (μm) to nanometres (nm). Formula: 1 um = x·1000 nm",
            listOf("x — a quantity in micrometres (μm)"),
            "the equivalent value in nanometres (nm)"
        ),
        "unit_dim" to d(
            "7D SI dimension (m, kg, s, A, K, mol, cd) of a unit-tagged value. Define: unit_dim(x, axis) = @unit_name(x, axis)",
            listOf("x — the unit-tagged value to inspect", "axis — optional axis selector (default \"dim\": 7D SI dimension; \"unit\": unit string; \"scale\": SI scale factor)"),
            "the 7D SI dimension descriptor (or axis-selected form)"
        ),
        "us_to_ms" to d(
            "Unit conversion from microseconds (μs) to milliseconds (ms). Formula: 1 us = x·0.001 ms",
            listOf("x — a quantity in microseconds (μs)"),
            "the equivalent value in milliseconds (ms)"
        ),
        "us_to_ns" to d(
            "Unit conversion from microseconds (μs) to nanoseconds (ns). Formula: 1 us = x·1000 ns",
            listOf("x — a quantity in microseconds (μs)"),
            "the equivalent value in nanoseconds (ns)"
        ),
        "van_der_waals_p" to d(
            "real-gas pressure via the van der Waals equation. Define: van_der_waals_p(n_mol, temp_k, vol_l, a_vdw, b_vdw) = ((n_mol·R_atm·temp_k) / (vol_l - n_mol·b_vdw)) - a_vdw·((n_mol / vol_l)^2)",
            listOf("n_mol — moles of gas", "temp_k — temperature (K)", "vol_l — container volume (L)", "a_vdw — attraction constant (atm·L²/mol²)", "b_vdw — co-volume (L/mol)"),
            "a pressure in pascals (Pa)"
        ),
        "vant_hoff_osmotic" to d(
            "osmotic pressure with van’t Hoff factor π = i·M·R·T. Define: vant_hoff_osmotic(i_vant, molarity_m, temp_k) = i_vant·molarity_m·R_atm·temp_k",
            listOf("i_vant — van’t Hoff factor i", "molarity_m — molarity (mol/L)", "temp_k — absolute temperature (K)"),
            "an osmotic pressure consistent with the unit set of R"
        ),
        "vital_capacity" to d(
            "vital capacity = IRV + TV + ERV. Define: vital_capacity(tv_ml, irv_ml, erv_ml) = tv_ml + irv_ml + erv_ml",
            listOf("tv_ml — tidal volume (mL)", "irv_ml — inspiratory reserve volume (mL)", "erv_ml — expiratory reserve volume (mL)"),
            "a capacity in mL"
        ),
        "water_potential" to d(
            "water potential Ψw = Ψs + Ψp. Define: water_potential(psi_s, psi_p) = psi_s + psi_p",
            listOf("psi_s — solute potential Ψs", "psi_p — pressure (turgor) potential Ψp"),
            "a potential in bars or MPa (per input unit set)"
        ),
        "weak_acid_h" to d(
            "hydronium concentration of a weak acid from Ka and concentration. Define: weak_acid_h(ka, conc_c) = √(ka·conc_c)",
            listOf("ka — acid dissociation constant Ka", "conc_c — initial acid concentration (mol/L)"),
            "a hydronium concentration in mol/L"
        ),
        "weak_acid_ph" to d(
            "pH of a weak acid solution from Ka and concentration. Define: weak_acid_ph(ka, conc_c) = -log₁₀(√(ka·conc_c))",
            listOf("ka — acid dissociation constant Ka", "conc_c — initial acid concentration (mol/L)"),
            "a pH value (dimensionless)"
        ),
        "wheatstone_r4" to d(
            "fourth bridge resistance of a balanced Wheatstone bridge. Define: wheatstone_r4(r1, r2, r3) = (r2·r3) / r1",
            listOf("r1 — first known resistance (Ω)", "r2 — second known resistance (Ω)", "r3 — third known resistance (Ω)"),
            "a resistance in ohms (Ω)"
        ),
        "wien_peak_lambda" to d(
            "Wien displacement law peak wavelength of blackbody radiation. Define: wien_peak_lambda(temp_k) = b_wien / temp_k",
            listOf("temp k — temperature (K)"),
            "a peak wavelength in metres (m)"
        ),
        "work_other_person" to d(
            "time the other person needs for the rest of a shared job. Define: work_other_person(d_both, d_one) = (d_both·d_one) / (d_one - d_both)",
            listOf("d_both — total days to finish together", "d_one — days the first person needed alone"),
            "the remaining time needed in the inputs’ time unit"
        ),
        "work_rate" to d(
            "work done per unit time (rate) from total work and time. Define: work_rate(days) = 1 / days",
            listOf("days — time taken to complete one full job"),
            "work per unit time in the inputs’ units"
        ),
        "work_rem_after" to d(
            "work remaining after a worker stops mid-job. Define: work_rem_after(d_total, days_worked) = 1 - (days_worked / d_total)",
            listOf("d_total — total days the job needs", "days_worked — days already worked"),
            "the remaining work in the same work unit as the inputs"
        ),
        "work_together_2" to d(
            "combined time for two workers with individual completion times. Define: work_together_2(d1, d2) = (d1·d2) / (d1 + d2)",
            listOf("d1 — days person 1 needs alone", "d2 — days person 2 needs alone"),
            "the combined completion time in the inputs’ time unit"
        ),
        "work_together_3" to d(
            "combined time for three workers with individual completion times. Define: work_together_3(d1, d2, d3) = (d1·d2·d3) / (d1·d2 + d2·d3 + d3·d1)",
            listOf("d1 — days person 1 needs alone", "d2 — days person 2 needs alone", "d3 — days person 3 needs alone"),
            "the combined completion time in the inputs’ time unit"
        ),
        "yd_to_m" to d(
            "Unit conversion from yards (yd) to metres (m). Formula: 1 yd = x·0.9144 m",
            listOf("x — a quantity in yards (yd)"),
            "the equivalent value in metres (m)"
        ),
        "young_fringe_width" to d(
            "double-slit fringe width β = λD/d. Define: young_fringe_width(wavelength_m, screen_d, slit_sep_d) = (wavelength_m·screen_d) / slit_sep_d",
            listOf("wavelength_m — light wavelength λ (m)", "screen_d — slit-screen distance D (m)", "slit_sep_d — slit separation d (m)"),
            "a fringe width in the input length unit"
        ),
        "youngs_modulus" to d(
            "Young’s modulus Y = stress/strain. Define: youngs_modulus(force_f, l0, area, dl) = (force_f·l0) / (area·dl)",
            listOf("force_f — applied force (N)", "l0 — original length (m)", "area — cross-section (m²)", "dl — extension ΔL (m)"),
            "a modulus in pascals (Pa) from the inputs' units"
        ),
        "zero_order_half_life" to d(
            "half-life of a zero-order reaction t½ = [A]₀/(2k). Define: zero_order_half_life(c0, rate_k) = c0 / (2·rate_k)",
            listOf("c0 — initial concentration [A]₀", "rate_k — zero-order rate constant k"),
            "a half-life in the units of [A]₀/k"
        ),
        )

    /** Lookup by function name; null when no curated entry exists. */
    fun forName(name: String): FnDoc? = BY_NAME[name]
}

/**
 * Rich hold-popup text for a Functions-tab catalog item. Prefers the curated
 * [CalculatorFunctionDocs] entry for this function (exact stdlib definition, explained
 * parameters with units, and a described return value); falls back to the generic
 * [CalculatorCatalogItem.toPopupInfoText] when the function is not documented (e.g. a
 * user-defined or patched symbol).
 */
fun CalculatorCatalogItem.richPopupText(): String {
    val doc = CalculatorFunctionDocs.forName(name) ?: return toPopupInfoText()
    val originLabel = when (originGroup) {
        "project1" -> "project1 • [$moduleName]" + (sourceFile?.let { " ($it)" } ?: "")
        "patch" -> "patch • [$moduleName]" + (sourceFile?.let { " ($it)" } ?: "")
        else -> "stdlib • [$moduleName]"
    }
    val kindLabel = when (kind) {
        "op" -> "Operator / Overload"
        "const" -> "Constant / Variable"
        else -> "Function"
    }
    val paramsBlock = if (doc.params.isEmpty()) "  • None — Takes no parameters"
    else doc.params.mapIndexed { idx, line -> "  ${idx + 1}. $line" }.joinToString("\n")
    return buildString {
        append(signature).append("\n")
        append("Source: ").append(originLabel).append(" • ").append(kindLabel).append("\n")
        append(doc.definition).append("\n")
        append("\nParameters / Operands:\n")
        append(paramsBlock).append("\n\n")
        append("Returns:\n  • ").append(doc.returns)
    }
}