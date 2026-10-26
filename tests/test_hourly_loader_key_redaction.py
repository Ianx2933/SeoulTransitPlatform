"""Contracts for keeping the Seoul Open API key out of loader errors.

The key is a path segment of the request URL, and requests copies that URL into
connection, timeout and HTTP error messages. A timeout once put the key into
Cloud Logging this way. These tests lock down that neither the message nor the
traceback of a failed request contains the key.
"""

from __future__ import annotations

import traceback

import pytest
import requests

from pipelines.hourly_od_estimation import load_hourly_boarding as loader

API_KEY = "abc123SECRETKEY987"


def failing_get(error_factory):
    def _get(url, timeout):
        assert API_KEY in url  # the key really is in the URL being requested
        raise error_factory(url)
    return _get


def http_error_response(url):
    response = requests.Response()
    response.status_code = 500
    response.reason = "Internal Server Error"
    response.url = url
    return response


@pytest.mark.parametrize("error_factory", [
    lambda url: requests.ConnectTimeout(f"HTTPConnectionPool: Max retries exceeded with url: {url}"),
    lambda url: requests.ConnectionError(f"Failed to establish a new connection: {url}"),
    lambda url: requests.ReadTimeout(f"Read timed out. (read timeout=30) {url}"),
], ids=["connect-timeout", "connection-error", "read-timeout"])
def test_transport_errors_do_not_expose_the_key(monkeypatch, error_factory):
    monkeypatch.setattr(loader.requests, "get", failing_get(error_factory))

    with pytest.raises(loader.SeoulApiRequestError) as caught:
        loader.fetch_page(API_KEY, 1, 1000, "202401")

    assert API_KEY not in str(caught.value)
    assert "***" in str(caught.value)
    rendered = "".join(traceback.format_exception(caught.value))
    assert API_KEY not in rendered


def test_http_errors_do_not_expose_the_key(monkeypatch):
    def _get(url, timeout):
        return http_error_response(url)

    monkeypatch.setattr(loader.requests, "get", _get)

    with pytest.raises(loader.SeoulApiRequestError) as caught:
        loader.fetch_page(API_KEY, 1, 1000, "202401")

    assert "HTTPError" in str(caught.value)
    assert API_KEY not in str(caught.value)
    assert API_KEY not in "".join(traceback.format_exception(caught.value))


def test_original_exception_is_not_chained():
    # `from None` is what keeps the original exception, and its URL, out of the
    # traceback. Losing it would silently reintroduce the leak.
    try:
        try:
            raise requests.ConnectTimeout(f"url: http://host/{API_KEY}/xml/")
        except requests.RequestException as error:
            raise loader.SeoulApiRequestError(loader.redact_key(str(error), API_KEY)) from None
    except loader.SeoulApiRequestError as wrapped:
        assert wrapped.__cause__ is None
        assert wrapped.__suppress_context__ is True


@pytest.mark.parametrize("text, key, expected", [
    (f"a/{API_KEY}/b/{API_KEY}", API_KEY, "a/***/b/***"),
    ("no key here", API_KEY, "no key here"),
    ("empty key leaves text alone", "", "empty key leaves text alone"),
])
def test_redact_key(text, key, expected):
    assert loader.redact_key(text, key) == expected
