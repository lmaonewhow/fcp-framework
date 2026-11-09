"""Lossless API values. Validation returns the original string, never normalized data."""
import datetime as dt
import re


class ContractError(ValueError):
    """An input is outside the reviewed contract."""


def text(value, maximum):
    if type(value) is not str or not 1 <= len(value) <= maximum:
        raise ContractError('Expected nonempty text within character limit')
    # Python can contain lone surrogate code points, which are not utf8mb4 text.
    try:
        value.encode('utf-8', errors='strict')
    except UnicodeError as exc:
        raise ContractError('Invalid Unicode scalar value') from exc
    return value


def tenant_id(value):
    """Trusted host value only; this validator does not establish trusted provenance."""
    return text(value, 100)


def unsigned_bigint(value, *, positive=False):
    if type(value) is not str or len(value) > 20 or not re.fullmatch(r'0|[1-9][0-9]*', value):
        raise ContractError('Expected canonical unsigned BIGINT decimal string')
    number = int(value)
    if number > 18446744073709551615 or (positive and number == 0):
        raise ContractError('Unsigned BIGINT outside allowed range')
    return value


def decimal_string(value, precision, scale, *, unit_scale=None):
    """No rounding, exponent, float conversion or loss of caller precision."""
    if (type(precision) is not int or type(scale) is not int or
            not 1 <= precision <= 65 or not 0 <= scale <= min(30, precision)):
        raise ContractError('Invalid DECIMAL declaration')
    allowed_scale = scale
    if unit_scale is not None:
        if type(unit_scale) is not int or not 0 <= unit_scale <= min(4, scale):
            raise ContractError('Invalid account unit scale')
        allowed_scale = unit_scale
    if (type(value) is not str or len(value) > precision + 2 or
            not re.fullmatch(r'-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?', value)):
        raise ContractError('Expected exact decimal string')
    whole, _, fraction = value.lstrip('-').partition('.')
    if len(whole.lstrip('0')) > precision - scale or len(fraction) > allowed_scale:
        raise ContractError('DECIMAL overflow or excess fraction; rounding forbidden')
    return value


def utc_timestamp(value):
    if type(value) is not str or not re.fullmatch(
            r'[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z', value):
        raise ContractError('Expected UTC timestamp with at most six fraction digits')
    try:
        parsed = dt.datetime.fromisoformat(value[:-1])
    except ValueError as exc:
        raise ContractError('Invalid UTC timestamp') from exc
    if parsed > dt.datetime(9999, 12, 31, 23, 59, 59, 499999):
        raise ContractError('Outside project DATETIME persistence range')
    return value


def business_date(value):
    """Calendar date in a separately resolved school timezone, not a UTC instant."""
    if type(value) is not str or not re.fullmatch(r'[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}', value):
        raise ContractError('Expected business date YYYY-MM-DD')
    try:
        dt.date.fromisoformat(value)
    except ValueError as exc:
        raise ContractError('Invalid business date') from exc
    return value
