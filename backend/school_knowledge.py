"""Verified official knowledge base for Montfort Senior Secondary School, Bagaha.

All facts in this module were directly verified from official school sources at
https://montfortbagaha.org/ including:
- Homepage: https://montfortbagaha.org/
- Mandatory Public Disclosure: https://montfortbagaha.org/mandatory-public-disclosure/
- Management Team: https://montfortbagaha.org/management-team/
- SARAS 6.0 Disclosure PDF: https://montfortbagaha.org/wp-content/uploads/2025/10/Mandatory-Disclosure-Details-_-SARAS-6.0.pdf

No unverified facts, invented names, or speculative details are included.
"""

from __future__ import annotations

from typing import Dict, Any

MONTFORT_BAGAHA_FACTS: Dict[str, Any] = {
    "school_name": "Montfort Senior Secondary School (Montfort Sr. Sec. School), Bagaha",
    "official_website": "https://montfortbagaha.org/",
    "address": "Bankatwa, Bagaha-1, West Champaran, Bihar - 845101",
    "founded_year": 2005,
    "establishment_date": "02-05-2005",
    "institution_type": (
        "Christian religious minority co-educational institution open to all students "
        "irrespective of religion, caste, or community"
    ),
    "managed_by": (
        "Montfort Brothers of St. Gabriel, Ranchi Province "
        "(Brothers of St. Gabriel Education Society)"
    ),
    "cbse_affiliation_number": "330507",
    "school_code": "65499",
    "principal": "Bro. Vincent Madhanu",
    "principal_qualification": "B.Ed., M.Ed.",
    "vice_principal": "Bro. Bimal Ekka",
    "vice_principal_qualification": "B.Ed.",
    "email": "montbagaha@gmail.com",
    "contact_numbers": ["06251-226503", "9955377902"],
    "student_clubs": [
        "Eco-Club",
        "Literary Club",
        "Science and Math Club",
        "Games & Sports Club",
    ],
    "mission_summary": (
        "To educate youth in a spirit of service to humanity through holistic, "
        "value-based, and knowledge-based education—promoting intellectual excellence, "
        "discipline, hard work, moral values, scientific temper, sportsmanship, "
        "and national integration."
    ),
}


def build_school_knowledge_prompt_block() -> str:
    """Return a clean, factual reference block for Gemini system instructions."""
    clubs = ", ".join(MONTFORT_BAGAHA_FACTS["student_clubs"])
    phones = ", ".join(MONTFORT_BAGAHA_FACTS["contact_numbers"])
    return (
        "VERIFIED MONTFORT SCHOOL BAGAHA KNOWLEDGE (from official https://montfortbagaha.org/):\n"
        f"- Official School Name: {MONTFORT_BAGAHA_FACTS['school_name']}\n"
        f"- Official Website: {MONTFORT_BAGAHA_FACTS['official_website']}\n"
        f"- Address: {MONTFORT_BAGAHA_FACTS['address']}\n"
        f"- Founded: {MONTFORT_BAGAHA_FACTS['founded_year']} ({MONTFORT_BAGAHA_FACTS['establishment_date']})\n"
        f"- Managed & Administered By: {MONTFORT_BAGAHA_FACTS['managed_by']}\n"
        f"- Institution Type: {MONTFORT_BAGAHA_FACTS['institution_type']}\n"
        f"- CBSE Affiliation No.: {MONTFORT_BAGAHA_FACTS['cbse_affiliation_number']} | "
        f"School Code: {MONTFORT_BAGAHA_FACTS['school_code']}\n"
        f"- Principal (officially confirmed): {MONTFORT_BAGAHA_FACTS['principal']} "
        f"({MONTFORT_BAGAHA_FACTS['principal_qualification']})\n"
        f"- Vice Principal (officially confirmed): {MONTFORT_BAGAHA_FACTS['vice_principal']} "
        f"({MONTFORT_BAGAHA_FACTS['vice_principal_qualification']})\n"
        f"- Official Email: {MONTFORT_BAGAHA_FACTS['email']} | Contact: {phones}\n"
        f"- Student Clubs: {clubs}\n"
        f"- Educational Mission: {MONTFORT_BAGAHA_FACTS['mission_summary']}\n"
        "STRICT RULE: Never invent unverified facts, rankings, or staff names about Montfort School Bagaha."
    )
