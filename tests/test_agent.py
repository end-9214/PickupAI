from agent import PhoneAssistant, INSTRUCTIONS
def test_agent_persona():
    a = PhoneAssistant()
    assert "करमवीर" in a.instructions or "Karamveer" in a.instructions
