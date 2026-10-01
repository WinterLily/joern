import 'override_privacy.dart';

class Foreign extends Base {
  String _pick(String value, String ignored) => ignored;
  String own(String value, String ignored) => _pick(value, ignored);
}
