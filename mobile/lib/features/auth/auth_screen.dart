import 'dart:async';

import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../../core/constants.dart';
import '../../core/theme/tokens.dart';
import '../../data/api_client.dart';
import '../../main.dart';
import '../../shared/mld_widgets.dart';

class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key});

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  bool _isSignUp = false;
  bool _busy = false;
  bool _obscurePassword = true;
  bool _obscureConfirmPassword = true;
  String? _errorMessage;

  // v2.9.6 r22: user-requested signup data — Gmail (email), name, AGE and
  // CLASS. Age is a numeric field; class is a picker (Class 6-12 /
  // University / Other — the Bangladesh student ladder).
  String? _selectedClass;
  static const _classOptions = [
    'Class 6', 'Class 7', 'Class 8', 'Class 9', 'Class 10',
    'Class 11', 'Class 12', 'University', 'Other',
  ];

  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final _ageController = TextEditingController();

  static bool _googleInitialized = false;

  @override
  void dispose() {
    _nameController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    _ageController.dispose();
    super.dispose();
  }

  Future<void> _submitEmailAuth() async {
    if (_busy) return;
    setState(() => _errorMessage = null);

    if (!_formKey.currentState!.validate()) return;

    final email = _emailController.text.trim();
    final password = _passwordController.text;

    if (_isSignUp) {
      if (password != _confirmPasswordController.text) {
        setState(() => _errorMessage = 'Passwords do not match');
        return;
      }
    }

    setState(() => _busy = true);

    try {
      bool ok = false;
      int? age;
      if (_isSignUp) {
        final name = _nameController.text.trim();
        final ageText = _ageController.text.trim();
        age = ageText.isEmpty ? null : int.tryParse(ageText);
        ok = await ApiClient.instance.registerWithEmail(
          email,
          password,
          displayName: name.isNotEmpty ? name : null,
          age: age,
          grade: _selectedClass,
        );
        if (ok) {
          // v2.9.6 r22: keep the entered profile on-device too (offline
          // mirror of the server copy).
          unawaited(ApiClient.instance.saveSignupProfile(
            displayName: name.isNotEmpty ? name : null,
            age: age,
            grade: _selectedClass,
          ));
        }
      } else {
        ok = await ApiClient.instance.loginWithEmail(email, password);
      }

      if (!mounted) return;

      if (ok) {
        _onAuthSuccess();
      } else {
        final err = ApiClient.instance.lastError ??
            (_isSignUp ? 'Registration failed. Try again.' : 'Invalid email or password.');
        setState(() => _errorMessage = err);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _errorMessage = 'Connection error. Check your internet.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _signInWithGoogle() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _errorMessage = null;
    });

    try {
      final google = GoogleSignIn.instance;
      if (!_googleInitialized) {
        if (AppConstants.googleServerClientId.isNotEmpty) {
          await google.initialize(serverClientId: AppConstants.googleServerClientId);
        } else {
          await google.initialize();
        }
        _googleInitialized = true;
      }

      final account = await google.authenticate();
      final idToken = account.authentication.idToken;

      if (idToken == null || idToken.isEmpty) {
        if (mounted) {
          setState(() => _errorMessage = 'Google did not return an ID token.');
        }
        return;
      }

      final ok = await ApiClient.instance.loginWithGoogle(idToken);
      if (!mounted) return;

      if (ok) {
        _onAuthSuccess();
      } else {
        final err = ApiClient.instance.lastError ?? 'Google sign-in failed.';
        setState(() => _errorMessage = err);
      }
    } on GoogleSignInException catch (e) {
      if (mounted && e.code != GoogleSignInExceptionCode.canceled) {
        setState(() => _errorMessage = 'Google sign-in error: ${e.code.name}');
      }
    } catch (e) {
      if (mounted) {
        setState(() => _errorMessage = 'Google sign-in failed. Check connection.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _onAuthSuccess() {
    unawaited(ApiClient.instance.ensureDeviceRegistered());
    unawaited(ApiClient.instance.flushEventQueue());

    final state = AppStateScope.of(context).state;
    String target = AppConstants.routeShell;
    if (!state.onboardingComplete) {
      target = AppConstants.routeOnboarding;
    } else if (!state.pactAccepted) {
      target = AppConstants.routePact;
    } else if (state.sessionActive || state.cageActive) {
      target = AppConstants.routeActiveSession;
    }

    Navigator.of(context).pushNamedAndRemoveUntil(target, (route) => false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      body: MLDAppBackdrop(
        child: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
              child: Form(
                key: _formKey,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // Brand Shield
                    Container(
                      width: 72,
                      height: 72,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [AppColors.primary, AppColors.primaryDim],
                        ),
                        borderRadius: BorderRadius.circular(20),
                        boxShadow: [
                          BoxShadow(
                            color: AppColors.primary.withValues(alpha: 0.35),
                            blurRadius: 30,
                            spreadRadius: 2,
                          ),
                        ],
                      ),
                      child: const Icon(Icons.shield_outlined, size: 36, color: Colors.white),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'MAXLEVEL DETOX',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                            fontSize: 22,
                            fontWeight: FontWeight.w900,
                            letterSpacing: 2,
                          ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      _isSignUp
                          ? 'Create your account to start detox'
                          : 'Sign in to access your detox account',
                      style: const TextStyle(
                        color: AppColors.textSecondary,
                        fontSize: 13,
                      ),
                    ),
                    const SizedBox(height: 28),

                    // Auth Mode Selector
                    Container(
                      height: 46,
                      padding: const EdgeInsets.all(4),
                      decoration: BoxDecoration(
                        color: AppColors.surface,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: AppColors.edge.withValues(alpha: 0.5)),
                      ),
                      child: Row(
                        children: [
                          Expanded(
                            child: GestureDetector(
                              onTap: () {
                                if (_isSignUp) {
                                  setState(() {
                                    _isSignUp = false;
                                    _errorMessage = null;
                                  });
                                }
                              },
                              child: Container(
                                decoration: BoxDecoration(
                                  color: !_isSignUp ? AppColors.primary : Colors.transparent,
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                alignment: Alignment.center,
                                child: Text(
                                  'Sign In',
                                  style: TextStyle(
                                    color: !_isSignUp ? Colors.white : AppColors.textSecondary,
                                    fontWeight: FontWeight.w700,
                                    fontSize: 14,
                                  ),
                                ),
                              ),
                            ),
                          ),
                          Expanded(
                            child: GestureDetector(
                              onTap: () {
                                if (!_isSignUp) {
                                  setState(() {
                                    _isSignUp = true;
                                    _errorMessage = null;
                                  });
                                }
                              },
                              child: Container(
                                decoration: BoxDecoration(
                                  color: _isSignUp ? AppColors.primary : Colors.transparent,
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                alignment: Alignment.center,
                                child: Text(
                                  'Sign Up',
                                  style: TextStyle(
                                    color: _isSignUp ? Colors.white : AppColors.textSecondary,
                                    fontWeight: FontWeight.w700,
                                    fontSize: 14,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 20),

                    // Error Message
                    if (_errorMessage != null) ...[
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                        decoration: BoxDecoration(
                          color: AppColors.danger.withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: AppColors.danger.withValues(alpha: 0.4)),
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.error_outline, size: 18, color: AppColors.danger),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                _errorMessage!,
                                style: const TextStyle(
                                  color: AppColors.danger,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 16),
                    ],

                    // Sign Up: Name Field
                    if (_isSignUp) ...[
                      _buildTextField(
                        controller: _nameController,
                        hint: 'Your Name (optional)',
                        icon: Icons.person_outline,
                        keyboardType: TextInputType.name,
                      ),
                      const SizedBox(height: 12),
                    ],

                    // Email Field (Gmail or any address)
                    _buildTextField(
                      controller: _emailController,
                      hint: 'Email address (Gmail)',
                      icon: Icons.email_outlined,
                      keyboardType: TextInputType.emailAddress,
                      validator: (v) {
                        if (v == null || v.trim().isEmpty) return 'Enter email';
                        if (!v.contains('@') || !v.contains('.')) return 'Invalid email';
                        return null;
                      },
                    ),
                    const SizedBox(height: 12),

                    // Sign Up: Age + Class (v2.9.6 r22 — user-requested
                    // signup data: Gmail, name, age, class)
                    if (_isSignUp) ...[
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: _buildTextField(
                              controller: _ageController,
                              hint: 'Age',
                              icon: Icons.cake_outlined,
                              keyboardType: TextInputType.number,
                              validator: (v) {
                                if (v == null || v.trim().isEmpty) return null;
                                final age = int.tryParse(v.trim());
                                if (age == null) return 'Numbers only';
                                if (age < 5 || age > 100) return 'Age 5-100';
                                return null;
                              },
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(child: _buildClassPicker()),
                        ],
                      ),
                      const SizedBox(height: 12),
                    ],

                    // Password Field
                    _buildTextField(
                      controller: _passwordController,
                      hint: 'Password (min 6 chars)',
                      icon: Icons.lock_outline,
                      obscureText: _obscurePassword,
                      suffixIcon: IconButton(
                        icon: Icon(
                          _obscurePassword ? Icons.visibility_off : Icons.visibility,
                          size: 18,
                          color: AppColors.textDisabled,
                        ),
                        onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                      ),
                      validator: (v) {
                        if (v == null || v.isEmpty) return 'Enter password';
                        if (v.length < 6) return 'Password must be at least 6 characters';
                        return null;
                      },
                    ),

                    // Sign Up: Confirm Password Field
                    if (_isSignUp) ...[
                      const SizedBox(height: 12),
                      _buildTextField(
                        controller: _confirmPasswordController,
                        hint: 'Confirm Password',
                        icon: Icons.lock_reset,
                        obscureText: _obscureConfirmPassword,
                        suffixIcon: IconButton(
                          icon: Icon(
                            _obscureConfirmPassword ? Icons.visibility_off : Icons.visibility,
                            size: 18,
                            color: AppColors.textDisabled,
                          ),
                          onPressed: () => setState(
                              () => _obscureConfirmPassword = !_obscureConfirmPassword),
                        ),
                        validator: (v) {
                          if (v == null || v.isEmpty) return 'Confirm your password';
                          if (v != _passwordController.text) return 'Passwords do not match';
                          return null;
                        },
                      ),
                    ],

                    const SizedBox(height: 22),

                    // Primary Submit Button
                    SizedBox(
                      width: double.infinity,
                      height: 48,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AppColors.primary,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                          elevation: 0,
                        ),
                        onPressed: _busy ? null : _submitEmailAuth,
                        child: _busy
                            ? const SizedBox(
                                width: 22,
                                height: 22,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              )
                            : Text(
                                _isSignUp ? 'Create Account' : 'Sign In',
                                style: const TextStyle(
                                  fontSize: 15,
                                  fontWeight: FontWeight.w700,
                                  color: Colors.white,
                                ),
                              ),
                      ),
                    ),

                    const SizedBox(height: 24),

                    // OR divider
                    Row(
                      children: [
                        Expanded(
                            child: Divider(color: AppColors.edge.withValues(alpha: 0.5))),
                        const Padding(
                          padding: EdgeInsets.symmetric(horizontal: 14),
                          child: Text(
                            'OR',
                            style: TextStyle(
                              color: AppColors.textDisabled,
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                              letterSpacing: 1.5,
                            ),
                          ),
                        ),
                        Expanded(
                            child: Divider(color: AppColors.edge.withValues(alpha: 0.5))),
                      ],
                    ),

                    const SizedBox(height: 24),

                    // Sign In with Google Button
                    SizedBox(
                      width: double.infinity,
                      height: 48,
                      child: OutlinedButton(
                        style: OutlinedButton.styleFrom(
                          backgroundColor: AppColors.surface,
                          side: BorderSide(color: AppColors.edge.withValues(alpha: 0.7)),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                        ),
                        onPressed: _busy ? null : _signInWithGoogle,
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Container(
                              width: 24,
                              height: 24,
                              alignment: Alignment.center,
                              decoration: const BoxDecoration(
                                color: Colors.white,
                                shape: BoxShape.circle,
                              ),
                              child: const Text(
                                'G',
                                style: TextStyle(
                                  color: Color(0xFF4285F4),
                                  fontSize: 14,
                                  fontWeight: FontWeight.w900,
                                ),
                              ),
                            ),
                            const SizedBox(width: 12),
                            const Text(
                              'Sign in with Google',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                                color: AppColors.textPrimary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// v2.9.6 r22: class picker styled to match the text fields — the
  /// signup form collects Gmail, name, age and class.
  Widget _buildClassPicker() {
    final border = OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: BorderSide(color: AppColors.edge.withValues(alpha: 0.5)),
    );
    return DropdownButtonFormField<String>(
      initialValue: _selectedClass,
      items: [
        for (final c in _classOptions)
          DropdownMenuItem(
            value: c,
            child: Text(c,
                style: const TextStyle(color: AppColors.textPrimary, fontSize: 14)),
          ),
      ],
      onChanged: (v) => setState(() => _selectedClass = v),
      style: const TextStyle(color: AppColors.textPrimary, fontSize: 14),
      dropdownColor: AppColors.surface,
      icon: const Icon(Icons.arrow_drop_down, color: AppColors.textSecondary),
      decoration: InputDecoration(
        hintText: 'Class',
        hintStyle: const TextStyle(color: AppColors.textDisabled, fontSize: 13),
        prefixIcon:
            const Icon(Icons.school_outlined, size: 20, color: AppColors.textSecondary),
        filled: true,
        fillColor: AppColors.surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        border: border,
        enabledBorder: border,
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.primary, width: 1.5),
        ),
      ),
    );
  }

  Widget _buildTextField({
    required TextEditingController controller,
    required String hint,
    required IconData icon,
    bool obscureText = false,
    TextInputType keyboardType = TextInputType.text,
    Widget? suffixIcon,
    String? Function(String?)? validator,
  }) {
    return TextFormField(
      controller: controller,
      obscureText: obscureText,
      keyboardType: keyboardType,
      validator: validator,
      style: const TextStyle(color: AppColors.textPrimary, fontSize: 14),
      decoration: InputDecoration(
        hintText: hint,
        hintStyle: const TextStyle(color: AppColors.textDisabled, fontSize: 13),
        prefixIcon: Icon(icon, size: 20, color: AppColors.textSecondary),
        suffixIcon: suffixIcon,
        filled: true,
        fillColor: AppColors.surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: AppColors.edge.withValues(alpha: 0.5)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: AppColors.edge.withValues(alpha: 0.5)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.danger),
        ),
      ),
    );
  }
}
