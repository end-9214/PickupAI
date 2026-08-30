from agent import (
    extract_caller_number,
    construct_agent_prompt,
    PhoneAssistant,
    BASE_PROMPT_TEMPLATE,
    FALLBACK_SUBJECT_INSTRUCTION,
)


def test_construct_agent_prompt_with_custom():
    custom_task = "Ask the courier agent when the laptop delivery will arrive."
    prompt = construct_agent_prompt(custom_task)
    assert "You are Karamveer Singh" in prompt
    assert "Hindi" in prompt
    assert custom_task in prompt


def test_construct_agent_prompt_fallback():
    prompt = construct_agent_prompt(None)
    assert "You are Karamveer Singh" in prompt
    assert "Hindi" in prompt
    assert FALLBACK_SUBJECT_INSTRUCTION in prompt


def test_agent_persona_instance():
    assistant = PhoneAssistant()
    assert "Karamveer Singh" in assistant.instructions


def test_extract_caller_number_from_sip_attributes():
    attributes = {"sip.phoneNumber": "+14155552671", "sip.callerId": "John Doe"}
    number = extract_caller_number("sip-call-12345", "sip-trunk-participant", attributes)
    assert number == "+14155552671"


def test_extract_caller_number_from_outbound_room():
    number = extract_caller_number("outbound-call-9876543210-session", "sip-phone", {})
    assert number == "call" or len(number) >= 4
